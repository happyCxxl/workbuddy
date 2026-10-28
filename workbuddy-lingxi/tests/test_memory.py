"""记忆：自动判断抽取、指令必办、冲突合并、物理删除、混合召回与低置信过滤。"""

import pytest
from conftest import FakeEmbedder, db_up, wipe_memories
from httpx2 import AsyncClient

from lingxi.app.config import Settings
from lingxi.db.engine import session_scope
from lingxi.db.models import Memory
from lingxi.db.repo.memory_repo import MemoryRepo
from lingxi.domain.llm.gateway import LlmGateway
from lingxi.domain.memory.service import extract_and_store, recall


@pytest.fixture
async def clean_memories(engine_fixture: None) -> None:
    """清空记忆表：保证用例可重复执行。"""
    await wipe_memories()


async def add_memory(
    kind: str,
    scope: str,
    content: str,
    work_dir: str | None = None,
    confidence: float = 0.9,
    embedding: list[float] | None = None,
) -> int:
    """直接入库一条记忆并返回 id。"""
    async with session_scope() as s:
        row = Memory(
            kind=kind, scope=scope, work_dir=work_dir, content=content, confidence=confidence, embedding=embedding
        )
        await MemoryRepo(s).insert(row)
        await s.commit()
        return row.id


async def visible_contents(work_dir: str) -> list[str]:
    """可见范围内记忆内容。"""
    async with session_scope() as s:
        rows = await MemoryRepo(s).list_for_scope(work_dir)
    return [m.content for m in rows]


async def test_extract_remembers_important_content(
    fake_server: tuple[Settings, list[dict[str, object]]], clean_memories: None
) -> None:
    """用户明说记住：抽取入库（带向量），且提示包含敏感硬过滤约束。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    settings, captured = fake_server
    gateway = LlmGateway(settings)
    await extract_and_store(999, "D:\\tmp", "记住我喜欢甜品", "好的老板", gateway, FakeEmbedder())
    assert "喜欢甜品" in await visible_contents("D:\\tmp")
    assert any("证件号码" in str(c.get("input")) for c in captured)
    async with session_scope() as s:
        rows = await MemoryRepo(s).list_for_scope("D:\\tmp")
    stored = [m for m in rows if m.content == "喜欢甜品"][0]
    assert stored.embedding is not None
    assert len(stored.embedding) == 1024


async def test_extract_forget_deletes_physically(
    fake_server: tuple[Settings, list[dict[str, object]]], clean_memories: None
) -> None:
    """指令忘掉：匹配记忆被物理删除。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    settings, _ = fake_server
    await add_memory("preference", "global", "我喜欢甜品")
    gateway = LlmGateway(settings)
    await extract_and_store(999, "D:\\tmp", "忘掉甜品", "好的", gateway, FakeEmbedder())
    assert await visible_contents("D:\\tmp") == []


async def test_low_confidence_not_stored(
    fake_server: tuple[Settings, list[dict[str, object]]], clean_memories: None
) -> None:
    """低置信候选不入库。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    settings, _ = fake_server
    gateway = LlmGateway(settings)
    await extract_and_store(999, "D:\\tmp", "低置信试试", "好的", gateway, FakeEmbedder())
    assert await visible_contents("D:\\tmp") == []


async def test_conflict_updates_existing(
    fake_server: tuple[Settings, list[dict[str, object]]], clean_memories: None
) -> None:
    """冲突合并：更新旧记忆内容，不重复新增。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    settings, _ = fake_server
    memory_id = await add_memory("preference", "global", "我喜欢甜品")
    gateway = LlmGateway(settings)
    await extract_and_store(999, "D:\\tmp", "更新一下", "好的", gateway, FakeEmbedder())
    async with session_scope() as s:
        row = await MemoryRepo(s).get(memory_id)
    assert row is not None
    assert row.content == "改为喜欢咸的"
    assert "改为喜欢咸的" in await visible_contents("D:\\tmp")


async def test_recall_scope_and_ranking(clean_memories: None) -> None:
    """混合召回：向量近邻（语义）+ 关键词精排；只含全局与本目录；低置信不注入。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    embedder = FakeEmbedder()
    await add_memory("preference", "global", "我喜欢甜品", embedding=await embedder.embed("甜品"))
    await add_memory("fact", "global", "我爱吃蛋糕", embedding=await embedder.embed("甜品"))  # 语义同"甜品"（模拟）
    await add_memory("fact", "global", "我爱打篮球", embedding=await embedder.embed("篮球"))
    await add_memory("fact", "dir", "项目使用 React", work_dir="D:\\a", embedding=await embedder.embed("React"))
    await add_memory("fact", "dir", "另一个项目使用 Vue", work_dir="D:\\b", embedding=await embedder.embed("Vue"))
    await add_memory("fact", "global", "低置信的事实", confidence=0.3, embedding=await embedder.embed("事实"))
    rows = await recall("D:\\a", "甜品", embedder)
    contents = [m.content for m in rows]
    assert "我爱吃蛋糕" in contents  # 语义向量命中：内容不含"甜品"字样
    assert "我喜欢甜品" in contents
    assert "项目使用 React" in contents
    assert "另一个项目使用 Vue" not in contents
    assert "低置信的事实" not in contents
    assert contents.index("我爱吃蛋糕") < contents.index("我爱打篮球") if "我爱打篮球" in contents else True


async def test_memory_api_list_and_delete(
    client: AsyncClient, fake_server: tuple[Settings, list[dict[str, object]]], clean_memories: None
) -> None:
    """记忆页直查与物理删除；删除不存在报 60002。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    memory_id = await add_memory("preference", "global", "我喜欢甜品")
    resp = await client.get("/memories", params={"work_dir": "D:\\tmp"})
    contents = [m["content"] for m in resp.json()["data"]["memories"]]
    assert "我喜欢甜品" in contents
    del_resp = await client.delete(f"/memories/{memory_id}")
    assert del_resp.json()["code"] == 0
    resp2 = await client.get("/memories", params={"work_dir": "D:\\tmp"})
    assert "我喜欢甜品" not in [m["content"] for m in resp2.json()["data"]["memories"]]
    gone = await client.delete(f"/memories/{memory_id}")
    assert gone.json()["code"] == 60002


async def test_turn_end_extraction_wired(
    client: AsyncClient, fake_server: tuple[Settings, list[dict[str, object]]], clean_memories: None
) -> None:
    """端到端：回合结束自动抽取，记忆页可见。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    resp = await client.post("/sessions", json={"work_dir": "D:\\tmp", "title": "记忆端到端"})
    sid = int(resp.json()["data"]["id"])
    async with client.stream("POST", f"/sessions/{sid}/turns", json={"content": "记住我喜欢甜品"}) as stream:
        async for _line in stream.aiter_lines():
            pass
    resp2 = await client.get("/memories", params={"work_dir": "D:\\tmp"})
    contents = [m["content"] for m in resp2.json()["data"]["memories"]]
    assert "喜欢甜品" in contents
