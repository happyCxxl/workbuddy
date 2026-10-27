"""应用装配：创建 FastAPI 实例并组装中间件、路由与异常出口。"""

import logging
from collections.abc import Awaitable, Callable
from uuid import uuid4

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from lingxi.api import health
from lingxi.app.config import Settings
from lingxi.app.lifespan import lifespan
from lingxi.contract import REQUEST_ID, ApiError, R


def create_app(settings: Settings) -> FastAPI:
    """构建应用：中间件 + 路由；业务组件随实现顺序逐步接入。"""
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")

    app = FastAPI(title="灵犀", lifespan=lifespan)

    @app.middleware("http")
    async def trace_request(request: Request, call_next: Callable[[Request], Awaitable[JSONResponse]]) -> JSONResponse:
        """请求追踪：透传或生成 request_id，回写响应头并注入上下文。"""
        request_id = request.headers.get("X-Request-Id") or uuid4().hex
        REQUEST_ID.set(request_id)
        response = await call_next(request)
        response.headers["X-Request-Id"] = request_id
        return response

    @app.exception_handler(ApiError)
    async def handle_api_error(request: Request, exc: ApiError) -> JSONResponse:
        """业务失败统一出口：HTTP 恒 200 + 信封（PY-DTO-005）。"""
        del request  # 出口统一处理，不使用请求体
        return JSONResponse(status_code=200, content=R.fail(exc.error, str(exc)).to_dict())

    app.include_router(health.router)
    return app
