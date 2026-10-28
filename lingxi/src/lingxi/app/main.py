"""应用装配：创建 FastAPI 实例并组装中间件、路由与异常出口。"""

import logging
from collections.abc import Awaitable, Callable
from uuid import uuid4

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from lingxi.api import health, memories, sessions, turns
from lingxi.app.config import Settings
from lingxi.app.lifespan import make_lifespan
from lingxi.contract import REQUEST_ID, ApiError, R
from lingxi.core.hooks import HookRegistry
from lingxi.core.loop import TurnRunner
from lingxi.domain.llm.gateway import LlmGateway
from lingxi.domain.memory.embedding import Embedder, OpenAICompatibleEmbedder
from lingxi.domain.memory.hook import make_memory_hook


def create_app(settings: Settings, embedder: Embedder | None = None) -> FastAPI:
    """构建应用：中间件 + 路由 + 回合执行器装配；embedder 可注入（测试用假向量，缺省用配置的 embedding API）。"""
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")

    app = FastAPI(title="灵犀", lifespan=make_lifespan(settings))
    gateway = LlmGateway(settings)
    effective_embedder = embedder or OpenAICompatibleEmbedder(settings.memory)
    hooks = HookRegistry()
    hooks.register("post_execute", make_memory_hook(gateway, effective_embedder))
    runner = TurnRunner(settings, gateway, hooks, effective_embedder)

    @app.middleware("http")
    async def trace_request(request: Request, call_next: Callable[[Request], Awaitable[JSONResponse]]) -> JSONResponse:
        """请求追踪：透传或生成 request_id，回写响应头并注入上下文。"""
        request_id = request.headers.get("X-Request-Id") or uuid4().hex
        REQUEST_ID.set(request_id)
        response = await call_next(request)
        response.headers["X-Request-Id"] = request_id
        return response

    # noinspection PyUnusedLocal —— 签名由框架固定（FastAPI 异常出口必须接收 request 与 exc），当前逻辑不用请求体
    @app.exception_handler(ApiError)
    async def handle_api_error(_request: Request, exc: ApiError) -> JSONResponse:
        """业务失败统一出口：HTTP 恒 200 + 信封（PY-DTO-005）。"""
        return JSONResponse(status_code=200, content=R.fail(exc.error, str(exc)).to_dict())

    app.include_router(health.router)
    app.include_router(sessions.router)
    app.include_router(memories.router)
    app.include_router(turns.make_router(runner))
    return app
