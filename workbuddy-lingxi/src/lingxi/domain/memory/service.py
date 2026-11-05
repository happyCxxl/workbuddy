"""记忆服务：回合结束由模型判断上下文抽取；指令必办；冲突更新；物理删除；混合召回注入。"""

import logging
import math
from typing import Literal

from pydantic import BaseModel

from lingxi.db.engine import session_scope
from lingxi.db.models import Memory
from lingxi.db.repo.memory_repo import MemoryRepo
from lingxi.domain.llm.gateway import LlmGateway
from lingxi.domain.memory.embedding import Embedder

logger = logging.getLogger(__name__)

LOW_CONFIDENCE_THRESHOLD = 0.6
RECALL_LIMIT = 8

SENSITIVE_BAN = "证件号码、银行卡号、账户密码、犯罪与自伤相关信息"


class MemoryUpdate(BaseModel):
    """冲突合并：更新已有记忆的内容。"""

    memory_id: int
    content: str


class MemoryItem(BaseModel):
    """新记忆条目。"""

    kind: Literal["preference", "ongoing", "fact"]
    scope: Literal["global", "dir"]
    content: str
    confidence: float


class MemoryDecision(BaseModel):
    """抽取决策：动作 + 删除话题 + 更新 + 新增。"""

    action: Literal["remember", "forget", "none"]
    forget_topics: list[str]
    items: list[MemoryItem]
    updates: list[MemoryUpdate]


async def extract_and_store(
    session_id: int,
    work_dir: str,
    user_content: str,
    assistant_content: str,
    gateway: LlmGateway,
    embedder: Embedder,
    user_id: int = 1,
) -> None:
    """回合结束抽取：判断 → 删除 → 合并更新 → 新增（带向量化）；全部落一个事务。"""
    async with session_scope() as s:
        existing = await MemoryRepo(s).list_for_scope(user_id, work_dir)
    decision = await gateway.structured(
        "memory_decision",
        MemoryDecision,
        {
            "work_dir": work_dir,
            "user_content": user_content,
            "assistant_content": assistant_content,
            "sensitive_ban": SENSITIVE_BAN,
            "existing": "\n".join(f"[记忆{m.id}]（{m.kind}，{m.scope}）{m.content}" for m in existing),
        },
    )
    async with session_scope() as s:
        repo = MemoryRepo(s)
        for topic in decision.forget_topics:
            await repo.delete_matching(user_id, topic, work_dir)
        for update in decision.updates:
            await repo.update_content(user_id, update.memory_id, update.content)
        for item in decision.items:
            if item.confidence < LOW_CONFIDENCE_THRESHOLD:
                continue
            embedding: list[float] | None = None
            try:
                embedding = await embedder.embed(item.content)
            except Exception as exc:
                # 向量化失败降级：仍入库，仅关键词召回可用
                logger.warning("记忆向量化失败，按无向量入库：%s", exc)
            row = Memory(
                user_id=user_id,
                kind=item.kind,
                scope=item.scope,
                work_dir=work_dir if item.scope == "dir" else None,
                content=item.content,
                source_session_id=session_id,
                confidence=item.confidence,
                embedding=embedding,
            )
            await repo.insert(row)
        await s.commit()


async def recall(
    work_dir: str, query: str, embedder: Embedder, limit: int = RECALL_LIMIT, user_id: int = 1
) -> list[Memory]:
    """混合召回：pgvector 向量近邻粗筛 + 关键词精排加权；向量通道失败时仅关键词兜底；低置信不返回。"""
    query_vec: list[float] | None = None
    try:
        query_vec = await embedder.embed_query(query)
    except Exception as exc:
        logger.warning("召回向量化失败，降级仅关键词召回：%s", exc)
    async with session_scope() as s:
        repo = MemoryRepo(s)
        vector_rows = await repo.search_by_vector(user_id, work_dir, query_vec, limit * 3) if query_vec else []
        keyword_rows = [m for m in await repo.list_for_scope(user_id, work_dir) if m.embedding is None]
    scored: list[tuple[float, Memory]] = []
    for row in vector_rows:
        sim = _cosine_similarity(query_vec or [], row.embedding or [])
        score = VECTOR_WEIGHT * sim + KEYWORD_WEIGHT * _keyword_score(query, row.content)
        scored.append((score, row))
    for row in keyword_rows:
        scored.append((KEYWORD_WEIGHT * _keyword_score(query, row.content), row))
    scored.sort(key=lambda pair: pair[0], reverse=True)
    return [m for _, m in scored[:limit] if m.confidence >= LOW_CONFIDENCE_THRESHOLD]


VECTOR_WEIGHT = 0.7
KEYWORD_WEIGHT = 0.3


def _cosine_similarity(left: list[float], right: list[float]) -> float:
    """归一化向量点积（嵌入模型已归一化）。"""
    if not left or not right:
        return 0.0
    return sum(a * b for a, b in zip(left, right, strict=False)) / math.sqrt(
        sum(a * a for a in left) * sum(b * b for b in right)
    )


def _keyword_score(query: str, content: str) -> float:
    """二字词窗口重叠打分：关键词精排。"""
    query_bigrams = {query[i : i + 2] for i in range(len(query) - 1)}
    if not query_bigrams:
        return 0.0
    hits = sum(1 for bigram in query_bigrams if bigram in content)
    return hits / len(query_bigrams)
