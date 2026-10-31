"""JWT 鉴权：只验签、不发令牌；令牌来自 HttpOnly Cookie（兼容 Authorization 头）。"""

import hmac
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import Annotated

import jwt
from fastapi import Depends, Header, Request
from redis.asyncio import Redis

from lingxi.app.config import AuthConfig
from lingxi.contract import ApiError, ErrorCode

_MIN_SECRET_LENGTH = 32

_KEY_PREFIX = "auth:token-version:"

_EUNAUTHORIZED = "登录已失效，请重新登录"

TOKEN_COOKIE = "wb_token"

CSRF_COOKIE = "XSRF-TOKEN"

CSRF_HEADER = "X-CSRF-Token"

_SAFE_METHODS = frozenset({"GET", "HEAD", "OPTIONS", "TRACE"})

TokenVersionReader = Callable[[int], Awaitable[int | None]]


@dataclass(frozen=True)
class CurrentUser:
    """认证后的调用者：身份来自 Java 签发的令牌。"""

    id: int
    username: str
    role: str
    token_version: int


class RedisTokenVersionReader:
    """从 Java 写入的 Redis key 读取用户当前令牌版本。"""

    def __init__(self, client: Redis) -> None:
        self._client = client

    async def __call__(self, user_id: int) -> int | None:
        """读当前版本；key 不存在返回 None（视为未知，调用方按未认证处理）。"""
        value = await self._client.get(f"{_KEY_PREFIX}{user_id}")
        if value is None:
            return None
        try:
            return int(value)
        except ValueError:
            return -1


def ensure_auth_configured(config: AuthConfig) -> None:
    """启动校验：启用鉴权时密钥必须存在且不少于 32 字符。"""
    if not config.enabled:
        return
    if len(config.secret) < _MIN_SECRET_LENGTH:
        raise RuntimeError("config.local.toml 的 [auth].secret 未配置或长度不足 32 字符")


def decode_token(token: str, secret: str) -> CurrentUser:
    """校验 HS256 签名与过期时间，返回调用者。"""
    try:
        payload: dict[str, object] = jwt.decode(  # pyright: ignore[reportUnknownMemberType]
            token,
            secret,
            algorithms=["HS256"],
            options={"require": ["exp", "sub"]},
        )
    except jwt.PyJWTError as exc:
        raise ApiError(ErrorCode.UNAUTHORIZED, _EUNAUTHORIZED) from exc
    subject = payload.get("sub")
    try:
        user_id = int(str(subject))
    except (TypeError, ValueError) as exc:
        raise ApiError(ErrorCode.UNAUTHORIZED, _EUNAUTHORIZED) from exc
    return CurrentUser(
        id=user_id,
        username=str(payload.get("username", "")),
        role=str(payload.get("role", "")),
        token_version=int(str(payload.get("tv", 0))),
    )


async def require_user(request: Request, authorization: str | None = Header(default=None)) -> CurrentUser:
    """FastAPI 依赖：取 Cookie 或 Bearer 令牌，验签并比对共享版本号。"""
    token: str | None = None
    if authorization and authorization.startswith("Bearer "):
        token = authorization[7:]
    else:
        token = request.cookies.get(TOKEN_COOKIE)
    if not token:
        raise ApiError(ErrorCode.UNAUTHORIZED, _EUNAUTHORIZED)
    settings = request.app.state.settings
    user = decode_token(token, settings.auth.secret)
    reader: TokenVersionReader = request.app.state.token_version_reader
    try:
        current_version = await reader(user.id)
    except Exception as exc:  # noqa: BLE001 缓存不可用时安全失败，不放行
        raise ApiError(ErrorCode.UNAUTHORIZED, _EUNAUTHORIZED) from exc
    if current_version is None or current_version != user.token_version:
        raise ApiError(ErrorCode.UNAUTHORIZED, _EUNAUTHORIZED)
    return user


async def require_csrf(request: Request) -> None:
    """Cookie 认证的写请求做双提交校验；显式 Bearer 客户端不受影响。"""
    if request.method in _SAFE_METHODS or request.url.path == "/health":
        return
    if request.headers.get("authorization"):
        return
    if request.cookies.get(TOKEN_COOKIE) is None:
        return
    header = request.headers.get(CSRF_HEADER)
    cookie = request.cookies.get(CSRF_COOKIE)
    if not header or not cookie or not hmac.compare_digest(header, cookie):
        raise ApiError(ErrorCode.FORBIDDEN, "CSRF 校验失败")


CurrentUserDep = Annotated[CurrentUser, Depends(require_user)]
