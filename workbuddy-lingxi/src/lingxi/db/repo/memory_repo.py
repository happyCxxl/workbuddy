"""记忆数据访问：只查写，按用户隔离（PY-DATA-002）。"""

from sqlalchemy import delete, select
from sqlalchemy.ext.asyncio import AsyncSession

from lingxi.db.models import Memory


class MemoryRepo:
    """封装记忆的查询、写入与物理删除方法；所有查询都限定用户。"""

    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def insert(self, row: Memory) -> None:
        """插入记忆：事务由调用方提交。"""
        self._session.add(row)
        await self._session.flush()

    async def get(self, user_id: int, memory_id: int) -> Memory | None:
        """按主键读；跨用户视为不存在。"""
        row = await self._session.get(Memory, memory_id)
        if row is None or row.user_id != user_id:
            return None
        return row

    async def delete(self, user_id: int, memory_id: int) -> bool:
        """物理删除：不存在或跨用户返回 False。"""
        row = await self.get(user_id, memory_id)
        if row is None:
            return False
        await self._session.delete(row)
        await self._session.flush()
        return True

    async def delete_matching(self, user_id: int, topic: str, work_dir: str) -> int:
        """按话题删除该用户可见范围内记忆（全局 + 本目录）：内容包含话题即删。"""
        stmt = (
            delete(Memory)
            .where(
                Memory.user_id == user_id,
                Memory.content.ilike(f"%{topic}%"),
                (Memory.scope == "global") | (Memory.work_dir == work_dir),
            )
            .returning(Memory.id)
        )
        ids = list((await self._session.scalars(stmt)).all())
        await self._session.flush()
        return len(ids)

    async def update_content(self, user_id: int, memory_id: int, content: str) -> bool:
        """更新内容（冲突合并）：不存在或跨用户返回 False。"""
        row = await self.get(user_id, memory_id)
        if row is None:
            return False
        row.content = content
        await self._session.flush()
        return True

    async def list_for_scope(self, user_id: int, work_dir: str) -> list[Memory]:
        """可见范围：该用户全局记忆 + 指定工作目录的目录级记忆。"""
        stmt = (
            select(Memory)
            .where(
                Memory.user_id == user_id,
                (Memory.scope == "global") | (Memory.work_dir == work_dir),
            )
            .order_by(Memory.updated_at.desc())
        )
        return list((await self._session.scalars(stmt)).all())

    async def list_all(self, user_id: int) -> list[Memory]:
        """该用户全量列表：记忆页直查。"""
        stmt = select(Memory).where(Memory.user_id == user_id).order_by(Memory.updated_at.desc())
        return list((await self._session.scalars(stmt)).all())

    async def search_by_vector(self, user_id: int, work_dir: str, vector: list[float], limit: int) -> list[Memory]:
        """向量近邻粗筛：该用户可见范围内按 cosine 距离取 top-N。"""
        stmt = (
            select(Memory)
            .where(
                Memory.user_id == user_id,
                Memory.embedding.is_not(None),
                (Memory.scope == "global") | (Memory.work_dir == work_dir),
            )
            .order_by(Memory.embedding.cosine_distance(vector))
            .limit(limit)
        )
        return list((await self._session.scalars(stmt)).all())
