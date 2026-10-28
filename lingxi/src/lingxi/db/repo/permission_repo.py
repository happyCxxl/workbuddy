"""权限规则数据访问：只查写，不含业务规则（PY-DATA-002）。"""

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from lingxi.db.models import PermissionRule


class PermissionRepo:
    """封装权限规则的查询与写入方法。"""

    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def insert(self, rule: PermissionRule) -> None:
        """插入规则：事务由调用方提交。"""
        self._session.add(rule)
        await self._session.flush()

    async def list_matching(self, tool: str, work_dir: str) -> list[PermissionRule]:
        """候选规则：工具名或通配，全局或本目录。"""
        stmt = select(PermissionRule).where(
            (PermissionRule.tool == tool) | (PermissionRule.tool == "*"),
            (PermissionRule.level == "global") | (PermissionRule.work_dir == work_dir),
        )
        return list((await self._session.scalars(stmt)).all())

    async def list_all(self) -> list[PermissionRule]:
        """全量规则：设置页直查。"""
        stmt = select(PermissionRule).order_by(PermissionRule.id)
        return list((await self._session.scalars(stmt)).all())

    async def get(self, rule_id: int) -> PermissionRule | None:
        """按主键读。"""
        return await self._session.get(PermissionRule, rule_id)

    async def delete(self, rule_id: int) -> bool:
        """删除规则：不存在返回 False。"""
        rule = await self.get(rule_id)
        if rule is None:
            return False
        await self._session.delete(rule)
        await self._session.flush()
        return True
