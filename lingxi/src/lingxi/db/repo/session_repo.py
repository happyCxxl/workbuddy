"""会话数据访问：只查写，不含业务规则（PY-DATA-002）。"""

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from lingxi.db.models import AiSession


class SessionRepo:
    """封装会话的查询与写入方法。"""

    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def insert(self, row: AiSession) -> None:
        """插入会话：事务由调用方提交。"""
        self._session.add(row)
        await self._session.flush()

    async def get(self, session_id: int) -> AiSession | None:
        """按主键读会话。"""
        return await self._session.get(AiSession, session_id)

    async def list_recent(self, limit: int = 50) -> list[AiSession]:
        """最近会话：按更新时间倒序（状态未删除的会话）。"""
        stmt = select(AiSession).where(AiSession.status == "active").order_by(AiSession.updated_at.desc()).limit(limit)
        return list((await self._session.scalars(stmt)).all())
