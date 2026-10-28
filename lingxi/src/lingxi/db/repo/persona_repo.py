"""人格版本数据访问：只查写，不含业务规则（PY-DATA-002）。"""

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from lingxi.db.models import PersonaVersion


class PersonaRepo:
    """封装人格版本的查询与写入方法。"""

    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def latest(self) -> PersonaVersion | None:
        """最新版本快照。"""
        stmt = select(PersonaVersion).order_by(PersonaVersion.version.desc()).limit(1)
        return await self._session.scalar(stmt)

    async def get_by_version(self, version: int) -> PersonaVersion | None:
        """按版本读快照：历史版本内容不可变。"""
        stmt = select(PersonaVersion).where(PersonaVersion.version == version)
        return await self._session.scalar(stmt)

    async def get_by_id(self, row_id: int) -> PersonaVersion | None:
        """按主键读快照。"""
        return await self._session.get(PersonaVersion, row_id)

    async def max_version(self) -> int:
        """当前最大版本号；无数据时为 0。"""
        stmt = select(func.coalesce(func.max(PersonaVersion.version), 0))
        return int((await self._session.scalar(stmt)) or 0)

    async def insert(self, row: PersonaVersion) -> None:
        """插入新版本：版本号由业务层生成。"""
        self._session.add(row)
        await self._session.flush()
