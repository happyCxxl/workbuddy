"""数据库引擎与会话：进程级单例，异步驱动；连接懒加载。"""

from collections.abc import AsyncGenerator
from contextlib import asynccontextmanager

from sqlalchemy.ext.asyncio import AsyncEngine, AsyncSession, async_sessionmaker, create_async_engine

_engine: AsyncEngine | None = None
_factory: async_sessionmaker[AsyncSession] | None = None


def init_engine(database_url: str) -> None:
    """创建进程级引擎与会话工厂（幂等；不建立实际连接）。"""
    global _engine, _factory
    if _engine is None:
        _engine = create_async_engine(database_url, pool_pre_ping=True)
        _factory = async_sessionmaker(_engine, expire_on_commit=False)


@asynccontextmanager
async def session_scope() -> AsyncGenerator[AsyncSession]:
    """单请求会话：调用方只拿会话，不管理生命周期。"""
    if _factory is None:
        raise RuntimeError("引擎未初始化：先调 init_engine")
    async with _factory() as session:
        yield session


async def dispose_engine() -> None:
    """释放引擎：应用关闭时调用。"""
    global _engine, _factory
    if _engine is not None:
        await _engine.dispose()
    _engine = None
    _factory = None
