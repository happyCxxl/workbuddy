"""应用生命周期：启动与关闭钩子，随实现顺序逐步接入组件。"""

from collections.abc import AsyncGenerator
from contextlib import asynccontextmanager

from fastapi import FastAPI


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncGenerator[None]:
    """启动与关闭：当前为空；数据库引擎等随后续步骤接入。"""
    yield
