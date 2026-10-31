"""会话数据访问：只查写，不含业务规则（PY-DATA-002）。"""

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from lingxi.db.models import AiSession


class SessionRepo:
    """封装会话的查询与写入方法；传入 user_id 时按归属过滤。"""

    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def insert(self, row: AiSession) -> None:
        """插入会话：事务由调用方提交。"""
        self._session.add(row)
        await self._session.flush()

    async def get(self, session_id: int, user_id: int | None = None) -> AiSession | None:
        """按主键读会话；带 user_id 时跨用户视为不存在。"""
        row = await self._session.get(AiSession, session_id)
        if row is not None and user_id is not None and row.user_id != user_id:
            return None
        return row

    async def list_recent(self, limit: int = 50, user_id: int | None = None) -> list[AiSession]:
        """最近对话会话：排除子会话与逻辑删除，按更新时间倒序。"""
        stmt = select(AiSession).where(AiSession.status == "active", AiSession.kind == "chat")
        if user_id is not None:
            stmt = stmt.where(AiSession.user_id == user_id)
        stmt = stmt.order_by(AiSession.updated_at.desc()).limit(limit)
        return list((await self._session.scalars(stmt)).all())
