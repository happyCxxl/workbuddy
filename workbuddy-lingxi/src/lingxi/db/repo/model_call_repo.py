"""模型调用明细数据访问：只查写，不含业务规则（PY-DATA-002）。"""

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from lingxi.db.models import ModelCall


class ModelCallRepo:
    """封装模型调用明细的写入与汇总方法。"""

    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def insert(self, call: ModelCall) -> None:
        """插入一条明细：事务由调用方提交。"""
        self._session.add(call)
        await self._session.flush()

    async def usage_summary(self, session_id: int) -> tuple[int, int, int, int, int]:
        """会话用量：调用次数、输入/输出 token、缓存命中/未命中的输入 token。"""
        stmt = select(
            func.count(ModelCall.id),
            func.coalesce(func.sum(ModelCall.prompt_tokens), 0),
            func.coalesce(func.sum(ModelCall.completion_tokens), 0),
            func.coalesce(func.sum(ModelCall.prompt_cache_hit_tokens), 0),
            func.coalesce(func.sum(ModelCall.prompt_cache_miss_tokens), 0),
        ).where(ModelCall.session_id == session_id)
        row = (await self._session.execute(stmt)).one()
        return int(row[0] or 0), int(row[1] or 0), int(row[2] or 0), int(row[3] or 0), int(row[4] or 0)
