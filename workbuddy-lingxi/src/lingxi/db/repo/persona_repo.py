"""人格数据访问：只查写，按用户隔离。"""

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from lingxi.db.models import PersonaVersion


class PersonaRepo:
    """封装人格版本的查询与写入；所有方法都要求用户 ID。"""

    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def current(self, user_id: int) -> PersonaVersion | None:
        """该用户启用中的版本。"""
        stmt = select(PersonaVersion).where(PersonaVersion.user_id == user_id, PersonaVersion.active.is_(True))
        return await self._session.scalar(stmt)

    async def get_by_version(self, user_id: int, version: int) -> PersonaVersion | None:
        """按用户与版本号读快照。"""
        stmt = select(PersonaVersion).where(PersonaVersion.user_id == user_id, PersonaVersion.version == version)
        return await self._session.scalar(stmt)

    async def get_by_id(self, user_id: int, row_id: int) -> PersonaVersion | None:
        """按主键读快照；跨用户视为不存在。"""
        row = await self._session.get(PersonaVersion, row_id)
        if row is None or row.user_id != user_id:
            return None
        return row

    async def max_version(self, user_id: int) -> int:
        """该用户当前最大版本号；无数据时为 0。"""
        stmt = select(func.coalesce(func.max(PersonaVersion.version), 0)).where(PersonaVersion.user_id == user_id)
        return int((await self._session.scalar(stmt)) or 0)

    async def list_all(self, user_id: int) -> list[PersonaVersion]:
        """该用户全部版本：按版本号倒序。"""
        stmt = select(PersonaVersion).where(PersonaVersion.user_id == user_id).order_by(PersonaVersion.version.desc())
        return list((await self._session.scalars(stmt)).all())

    async def insert(self, row: PersonaVersion) -> None:
        """插入新版本：版本号由业务层生成。"""
        self._session.add(row)
        await self._session.flush()

    async def set_active(self, user_id: int, version: int) -> None:
        """把该用户的指定版本置为启用，其余取消。"""
        for row in await self.list_all(user_id):
            row.active = row.version == version
        await self._session.flush()
