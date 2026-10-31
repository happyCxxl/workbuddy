"""JWT 鉴权：公开路径、签名/过期、共享版本号与 Redis 不可用时的拒绝策略。"""

from collections.abc import Awaitable, Callable

import pytest
from httpx2 import AsyncClient

UNAUTHORIZED = 60003


async def test_health_is_public(anonymous_client: AsyncClient) -> None:
    """健康检查不要求令牌。"""
    response = await anonymous_client.get("/health")
    assert response.json()["code"] == 0


async def test_sessions_requires_token(anonymous_client: AsyncClient) -> None:
    """无 Authorization 头：统一未认证码。"""
    response = await anonymous_client.get("/sessions")
    assert response.json()["code"] == UNAUTHORIZED


async def test_sessions_rejects_malformed_header(anonymous_client: AsyncClient) -> None:
    """缺少 Bearer 前缀按未认证处理。"""
    response = await anonymous_client.get("/sessions", headers={"Authorization": "token"})
    assert response.json()["code"] == UNAUTHORIZED


async def test_sessions_rejects_bad_signature(anonymous_client: AsyncClient, token_factory: Callable[..., str]) -> None:
    """签名密钥不匹配：拒绝。"""
    token = token_factory(secret="x" * 40)
    response = await anonymous_client.get("/sessions", headers={"Authorization": f"Bearer {token}"})
    assert response.json()["code"] == UNAUTHORIZED


async def test_sessions_rejects_expired_token(anonymous_client: AsyncClient, token_factory: Callable[..., str]) -> None:
    """已过期令牌：拒绝。"""
    token = token_factory(ttl_seconds=-10)
    response = await anonymous_client.get("/sessions", headers={"Authorization": f"Bearer {token}"})
    assert response.json()["code"] == UNAUTHORIZED


@pytest.mark.usefixtures("engine_fixture")
async def test_sessions_accepts_valid_token(client: AsyncClient) -> None:
    """默认 client 携带合法令牌且版本一致：业务接口放行。"""
    response = await client.get("/sessions")
    assert response.json()["code"] == 0


async def test_rejects_stale_token_version(
    client_factory: Callable[..., Awaitable[AsyncClient]],
    token_factory: Callable[..., str],
) -> None:
    """共享版本号已递增：旧令牌拒绝。"""

    async def current_version(_user_id: int) -> int:
        return 2

    client = await client_factory(current_version, token_factory(token_version=1))
    response = await client.get("/sessions")
    assert response.json()["code"] == UNAUTHORIZED


async def test_rejects_unknown_token_version(
    client_factory: Callable[..., Awaitable[AsyncClient]],
    token_factory: Callable[..., str],
) -> None:
    """Redis 中没有该用户的版本：按未认证处理。"""

    async def missing_version(_user_id: int) -> int | None:
        return None

    client = await client_factory(missing_version, token_factory())
    response = await client.get("/sessions")
    assert response.json()["code"] == UNAUTHORIZED


async def test_rejects_when_version_store_unavailable(
    client_factory: Callable[..., Awaitable[AsyncClient]],
    token_factory: Callable[..., str],
) -> None:
    """Redis 不可用：安全失败，不放行。"""

    async def broken_reader(_user_id: int) -> int | None:
        raise RuntimeError("redis down")

    client = await client_factory(broken_reader, token_factory())
    response = await client.get("/sessions")
    assert response.json()["code"] == UNAUTHORIZED
