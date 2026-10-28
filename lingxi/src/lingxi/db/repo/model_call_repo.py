"""模型调用明细数据访问：只查写，不含业务规则（PY-DATA-002）。"""

from sqlalchemy.ext.asyncio import AsyncSession

from lingxi.db.models import ModelCall


class ModelCallRepo:
    """封装模型调用明细的写入方法。"""

    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def insert(self, call: ModelCall) -> None:
        """插入一条明细：事务由调用方提交。"""
        self._session.add(call)
        await self._session.flush()
