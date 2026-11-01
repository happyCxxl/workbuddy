"""应用装配：创建 FastAPI 实例并组装中间件、路由与异常出口。"""

import logging
from collections.abc import Awaitable, Callable
from uuid import uuid4

from fastapi import Depends, FastAPI, Request
from fastapi.responses import JSONResponse
from redis.asyncio import Redis

from lingxi.api import fs, health, memories, persona, sessions, turns, workspaces
from lingxi.app.auth import (
    RedisTokenVersionReader,
    TokenVersionReader,
    ensure_auth_configured,
    require_csrf,
    require_user,
)
from lingxi.app.config import RedisConfig, Settings
from lingxi.app.lifespan import make_lifespan
from lingxi.contract import REQUEST_ID, ApiError, R
from lingxi.core.hooks import HookRegistry
from lingxi.core.loop import TurnRunner
from lingxi.core.sub_sessions import SubSessionManager
from lingxi.domain.llm.gateway import LlmGateway
from lingxi.domain.memory.embedding import Embedder, OpenAICompatibleEmbedder
from lingxi.domain.memory.hook import make_memory_hook
from lingxi.domain.permission.gate import PermissionGate
from lingxi.domain.sub_session.service import mark_stale_running
from lingxi.domain.tools.fs import register_fs_tools
from lingxi.domain.tools.registry import ToolRegistry
from lingxi.domain.tools.shell import register_shell_tools
from lingxi.domain.tools.web import register_web_tools
from lingxi.model_gateway.api_admin import router as model_admin_router
from lingxi.model_gateway.api_capability import router as model_capability_router


def _redis_url(config: RedisConfig) -> str:
    """拼装 Redis 连接串；密码为空时省略认证段。"""
    auth = f":{config.password}@" if config.password else ""
    return f"redis://{auth}{config.host}:{config.port}/{config.db}"


def create_app(
    settings: Settings,
    embedder: Embedder | None = None,
    token_version_reader: TokenVersionReader | None = None,
) -> FastAPI:
    """构建应用：中间件 + 路由 + 回合执行器装配；embedder 与版本读取器可注入（测试用）。"""
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    ensure_auth_configured(settings.auth)

    app = FastAPI(title="灵犀", lifespan=make_lifespan(settings, startup=mark_stale_running))
    reader = token_version_reader
    if reader is None:
        client = Redis.from_url(  # pyright: ignore[reportUnknownMemberType]
            _redis_url(settings.redis), decode_responses=True
        )
        reader = RedisTokenVersionReader(client)
    app.state.settings = settings
    app.state.token_version_reader = reader
    gateway = LlmGateway(settings)
    effective_embedder = embedder or OpenAICompatibleEmbedder(settings.memory)
    tools = ToolRegistry()
    register_fs_tools(tools)
    register_web_tools(tools, settings)
    register_shell_tools(tools, settings)
    permission = PermissionGate()
    hooks = HookRegistry()
    hooks.register("post_execute", make_memory_hook(gateway, effective_embedder))
    runner = TurnRunner(settings, gateway, hooks, effective_embedder, tools, permission)
    sub_sessions = SubSessionManager(runner)

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

    protected = [Depends(require_user), Depends(require_csrf)]
    app.include_router(health.router)
    app.include_router(fs.router, dependencies=protected)
    app.include_router(workspaces.router, dependencies=protected)
    app.include_router(sessions.make_router(settings, sub_sessions), dependencies=protected)
    app.include_router(memories.router, dependencies=protected)
    app.include_router(persona.router, dependencies=protected)
    app.include_router(turns.make_router(runner), dependencies=protected)
    app.include_router(model_admin_router, dependencies=[Depends(require_csrf)])
    app.include_router(model_capability_router)
    return app
