"""应用生命周期：启动与关闭钩子，随实现顺序逐步接入组件。"""

import logging
from collections.abc import AsyncGenerator, Callable
from contextlib import AbstractAsyncContextManager, asynccontextmanager

from fastapi import FastAPI

from lingxi.app.config import Settings
from lingxi.db.engine import dispose_engine, init_engine

logger = logging.getLogger(__name__)


def make_lifespan(settings: Settings) -> Callable[[FastAPI], AbstractAsyncContextManager[None]]:
    """构造生命周期钩子：闭包持有配置，不依赖框架实例属性。"""

    # noinspection PyUnusedLocal —— 签名由框架固定（starlette 会传 app 实例），当前逻辑不使用
    @asynccontextmanager
    async def lifespan(_app: FastAPI) -> AsyncGenerator[None]:
        """启动：初始化数据库引擎（连接懒加载，不阻塞启动）；关闭：释放引擎。"""
        init_engine(settings.database_url)
        logger.info("应用启动：数据库引擎已初始化")
        try:
            yield
        finally:
            await dispose_engine()

    return lifespan
