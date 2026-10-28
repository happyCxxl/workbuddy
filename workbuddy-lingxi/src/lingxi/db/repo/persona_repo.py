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

    async def current(self) -> PersonaVersion | None:
        """启用中的版本；没有标记时回落最新版本。"""
        stmt = (
            select(PersonaVersion)
            .where(PersonaVersion.active.is_(True))
            .order_by(PersonaVersion.version.desc())
            .limit(1)
        )
        row = await self._session.scalar(stmt)
        return row if row is not None else await self.latest()

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

    async def list_all(self) -> list[PersonaVersion]:
        """全部版本：按版本号倒序。"""
        stmt = select(PersonaVersion).order_by(PersonaVersion.version.desc())
        return list((await self._session.scalars(stmt)).all())

    async def insert(self, row: PersonaVersion) -> None:
        """插入新版本：版本号由业务层生成。"""
        self._session.add(row)
        await self._session.flush()

    async def set_active(self, version: int) -> None:
        """把指定版本置为启用，其余取消。"""
        for row in await self.list_all():
            row.active = row.version == version
        await self._session.flush()
