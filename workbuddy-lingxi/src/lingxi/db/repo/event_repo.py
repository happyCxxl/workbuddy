"""事件日志数据访问：只查写，不含业务规则（PY-DATA-002）。"""

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from lingxi.db.models import SessionEvent


class EventRepo:
    """封装会话的查询与写入方法。"""

    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def max_seq(self, session_id: int) -> int:
        """会话内当前最大 seq；无事件时为 0。"""
        stmt = select(func.coalesce(func.max(SessionEvent.seq), 0)).where(SessionEvent.session_id == session_id)
        return int((await self._session.scalar(stmt)) or 0)

    async def insert(self, event: SessionEvent) -> None:
        """插入事件：同会话同 seq 由唯一键拦截。"""
        self._session.add(event)
        await self._session.flush()

    async def get(self, session_id: int, seq: int) -> SessionEvent | None:
        """按会话与 seq 取单条。"""
        stmt = select(SessionEvent).where(SessionEvent.session_id == session_id, SessionEvent.seq == seq)
        return await self._session.scalar(stmt)

    async def list_after(self, session_id: int, after_seq: int, limit: int | None) -> list[SessionEvent]:
        """after_seq 之后按 seq 升序；limit 为空表示不限。"""
        stmt = (
            select(SessionEvent)
            .where(SessionEvent.session_id == session_id, SessionEvent.seq > after_seq)
            .order_by(SessionEvent.seq)
        )
        if limit is not None:
            stmt = stmt.limit(limit)
        result = await self._session.scalars(stmt)
        return list(result)
