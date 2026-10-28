"""记忆抽取钩子：回合结束由结构化通道判断上下文并入库；失败只记日志，不阻断回合。"""

import logging

from lingxi.core.hooks import HookFunc, TurnContext
from lingxi.domain.llm.gateway import LlmGateway
from lingxi.domain.memory.embedding import Embedder
from lingxi.domain.memory.service import extract_and_store

logger = logging.getLogger(__name__)


def make_memory_hook(gateway: LlmGateway, embedder: Embedder) -> HookFunc:
    """构造 post_execute 记忆钩子：闭包持有统一出口与嵌入器。"""

    async def hook(ctx: TurnContext) -> None:
        try:
            await extract_and_store(
                ctx.session_id,
                str(ctx.payload["work_dir"]),
                str(ctx.payload["user_content"]),
                str(ctx.payload["assistant_content"]),
                gateway,
                embedder,
            )
        except Exception as exc:
            # 记忆抽取失败只记日志：不影响回合结果
            logger.warning("记忆抽取失败：%s", exc)

    return hook
