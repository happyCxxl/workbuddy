"""队列单写者：按会话串行化所有队列操作，避免撤回与消费并发写库。"""

import asyncio

from lingxi.db.models import SessionInputQueue
from lingxi.domain.queue import service


class QueueCoordinator:
    """每个会话一把异步锁；所有队列读改写都经过这里。"""

    def __init__(self) -> None:
        self._locks: dict[int, asyncio.Lock] = {}

    def _lock(self, session_id: int) -> asyncio.Lock:
        """取会话锁；同一事件循环内唯一。"""
        return self._locks.setdefault(session_id, asyncio.Lock())

    async def _run(self, session_id: int, operation: str, *args: object) -> object:
        """在会话锁内执行一个服务函数。"""
        async with self._lock(session_id):
            return await getattr(service, operation)(*args)

    async def list_pending(self, session_id: int) -> list[SessionInputQueue]:
        """串行读取待消费队列。"""
        return await self._run(session_id, "list_pending", session_id)  # type: ignore[return-value]

    async def enqueue(self, session_id: int, content: str) -> int:
        """串行追加排队消息。"""
        return await self._run(session_id, "enqueue", session_id, content)  # type: ignore[return-value]

    async def update(self, session_id: int, item_id: int, content: str) -> bool:
        """串行编辑排队消息。"""
        return await self._run(session_id, "update_item", session_id, item_id, content)  # type: ignore[return-value]

    async def cancel(self, session_id: int, item_id: int) -> tuple[bool, str | None]:
        """串行撤回；返回是否成功与失败原因。"""
        async with self._lock(session_id):
            status = await service.item_status(session_id, item_id)
            if status is None:
                return False, "not_found"
            if status not in ("pending", "claimed"):
                return False, "already_injected"
            removed = await service.cancel_item(session_id, item_id)
            return removed, None if removed else "already_injected"

    async def reorder(self, session_id: int, item_ids: list[int]) -> bool:
        """串行重排。"""
        return await self._run(session_id, "reorder", session_id, item_ids)  # type: ignore[return-value]

    async def claim_pending(self, session_id: int) -> list[SessionInputQueue]:
        """串行认领当前待消费项。"""
        return await self._run(session_id, "claim_pending", session_id)  # type: ignore[return-value]

    async def claim_item(self, session_id: int, item_id: int) -> SessionInputQueue | None:
        """串行认领指定项。"""
        return await self._run(session_id, "claim_item", session_id, item_id)  # type: ignore[return-value]

    async def is_claimed(self, session_id: int, item_id: int) -> bool:
        """串行检查认领状态。"""
        return await self._run(session_id, "is_claimed", session_id, item_id)  # type: ignore[return-value]

    async def mark_injected(self, session_id: int, item_id: int) -> None:
        """串行标记已注入。"""
        await self._run(session_id, "mark_injected", session_id, item_id)


coordinator = QueueCoordinator()
