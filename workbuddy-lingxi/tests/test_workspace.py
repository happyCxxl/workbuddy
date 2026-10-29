"""工作区接口：创建/列表/校验，会话绑定工作区。"""

from pathlib import Path

import pytest
from conftest import db_up
from httpx2 import AsyncClient


async def test_workspace_create_list_and_validation(client: AsyncClient, engine_fixture: None, tmp_path: Path) -> None:
    """创建工作区：默认取目录名；重复路径与不存在目录都报 60001。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    resolved = tmp_path
    created = (await client.post("/workspaces", json={"path": str(resolved)})).json()
    assert created["code"] == 0
    data = created["data"]
    assert data["path"] == str(resolved)
    assert data["name"] == resolved.name
    listed = (await client.get("/workspaces")).json()["data"]["workspaces"]
    assert any(item["id"] == data["id"] for item in listed)

    duplicate = (await client.post("/workspaces", json={"path": str(resolved)})).json()
    assert duplicate["code"] == 60001
    missing = (await client.post("/workspaces", json={"path": str(resolved / "no-such-dir")})).json()
    assert missing["code"] == 60001


async def test_session_binds_workspace(client: AsyncClient, engine_fixture: None, tmp_path: Path) -> None:
    """建会话只给 workspace_id：工作目录取自工作区，会话回带 workspace_id。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    resolved = tmp_path
    workspace = (await client.post("/workspaces", json={"path": str(resolved), "name": "测试工作区"})).json()["data"]
    created = (await client.post("/sessions", json={"workspace_id": workspace["id"]})).json()
    assert created["code"] == 0
    sid = int(created["data"]["id"])
    detail = (await client.get(f"/sessions/{sid}")).json()["data"]
    assert detail["work_dir"] == str(resolved)
    assert detail["workspace_id"] == workspace["id"]
    rows = (await client.get("/sessions")).json()["data"]["sessions"]
    mine = next(item for item in rows if item["id"] == str(sid))
    assert mine["workspace_id"] == workspace["id"]


async def test_session_requires_workspace_or_dir(client: AsyncClient, engine_fixture: None) -> None:
    """既没 workspace_id 也没 work_dir：报 60001。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    resp = (await client.post("/sessions", json={})).json()
    assert resp["code"] == 60001
