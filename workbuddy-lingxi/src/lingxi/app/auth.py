"""JWT 鉴权：只验签、不发令牌；密钥来自 config.local.toml，与 Java 服务一致。"""

from collections.abc import Awaitable, Callable
from dataclasses import dataclass

import jwt
from fastapi import Header, Request
from redis.asyncio import Redis

from lingxi.app.config import AuthConfig
from lingxi.contract import ApiError, ErrorCode

_MIN_SECRET_LENGTH = 32

_KEY_PREFIX = "auth:token-version:"

_EUNAUTHORIZED = "登录已失效，请重新登录"

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
    """FastAPI 依赖：验签 + 比对共享版本号，失败走统一未认证错误码。"""
    if not authorization or not authorization.startswith("Bearer "):
        raise ApiError(ErrorCode.UNAUTHORIZED, _EUNAUTHORIZED)
    settings = request.app.state.settings
    user = decode_token(authorization[7:], settings.auth.secret)
    reader: TokenVersionReader = request.app.state.token_version_reader
    try:
        current_version = await reader(user.id)
    except Exception as exc:  # noqa: BLE001 缓存不可用时安全失败，不放行
        raise ApiError(ErrorCode.UNAUTHORIZED, _EUNAUTHORIZED) from exc
    if current_version is None or current_version != user.token_version:
        raise ApiError(ErrorCode.UNAUTHORIZED, _EUNAUTHORIZED)
    return user
