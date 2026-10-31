"""多用户隔离：会话、工作区与人格互不可见、互不可改。"""

from collections.abc import Awaitable, Callable
from pathlib import Path

import pytest
from conftest import db_up
from httpx2 import AsyncClient

NOT_FOUND = 60002


async def _version_zero(_user_id: int) -> int:
    return 0


@pytest.mark.usefixtures("engine_fixture")
async def test_sessions_are_isolated(
    client: AsyncClient,
    client_factory: Callable[..., Awaitable[AsyncClient]],
    token_factory: Callable[..., str],
    tmp_path: Path,
) -> None:
    """用户 1 的会话，用户 2 既看不到也读不到。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    created = await client.post("/sessions", json={"work_dir": str(tmp_path), "title": "用户1会话"})
    session_id = created.json()["data"]["id"]
    other = await client_factory(_version_zero, token_factory(user_id=2))
    listed = await other.get("/sessions")
    assert session_id not in [row["id"] for row in listed.json()["data"]["sessions"]]
    detail = await other.get(f"/sessions/{session_id}")
    assert detail.json()["code"] == NOT_FOUND


@pytest.mark.usefixtures("engine_fixture")
async def test_workspaces_are_isolated(
    client: AsyncClient,
    client_factory: Callable[..., Awaitable[AsyncClient]],
    token_factory: Callable[..., str],
    tmp_path: Path,
) -> None:
    """用户 1 的工作区，用户 2 不能改名或删除。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    created = await client.post("/workspaces", json={"path": str(tmp_path), "name": "用户1工作区"})
    workspace_id = created.json()["data"]["id"]
    other = await client_factory(_version_zero, token_factory(user_id=2))
    renamed = await other.patch(f"/workspaces/{workspace_id}", json={"name": "抢改"})
    assert renamed.json()["code"] == NOT_FOUND
    removed = await other.delete(f"/workspaces/{workspace_id}")
    assert removed.json()["code"] == NOT_FOUND


@pytest.mark.usefixtures("engine_fixture")
async def test_persona_is_isolated(
    client: AsyncClient,
    client_factory: Callable[..., Awaitable[AsyncClient]],
    token_factory: Callable[..., str],
) -> None:
    """用户 1 保存人格不影响用户 2 的默认人格。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    fields = {
        "name": "用户1专属",
        "opening": "你好",
        "catchphrases": ["收到"],
        "tone": "直接",
        "style": "简洁",
        "boundaries": "先问再做",
        "user_name": "老板",
        "user_profile": "",
    }
    saved = await client.put("/persona", json={"fields": fields})
    assert saved.json()["code"] == 0
    other = await client_factory(_version_zero, token_factory(user_id=2))
    current = await other.get("/persona")
    assert current.json()["data"]["fields"]["name"] != "用户1专属"
