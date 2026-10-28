"""后台任务：干净上下文（无人格/记忆）、父会话事件、审批自动拒绝、嵌套拒绝与重启清理。"""

import asyncio

import pytest
from conftest import FakeEmbedder, db_up
from httpx2 import AsyncClient

from lingxi.app.config import Settings
from lingxi.contract import ApiError
from lingxi.core.hooks import HookRegistry
from lingxi.core.log import replay_events
from lingxi.core.loop import TurnRunner
from lingxi.core.sub_sessions import SubSessionManager
from lingxi.domain.llm.gateway import LlmGateway
from lingxi.domain.permission.gate import PermissionGate
from lingxi.domain.session.service import create_session, get_session
from lingxi.domain.sub_session.service import (
    create_sub_session,
    get_sub_session,
    mark_stale_running,
)
from lingxi.domain.tools.fs import register_fs_tools
from lingxi.domain.tools.registry import ToolRegistry


def make_registry() -> ToolRegistry:
    """标准工具注册表。"""
    registry = ToolRegistry()
    register_fs_tools(registry)
    return registry


def small_settings(settings: Settings) -> Settings:
    """以 fake 的 llm/memory 构造小循环配置（步数少，审批拒绝场景快速收敛）。"""
    return Settings(
        server={},
        database={},
        tools={},
        memory=settings.memory.model_dump(),
        llm=settings.llm.model_dump(),
        permission={"preset": "standard"},
        loop={"input_token_budget": 100000, "context_budget_ratio": 0.8, "max_steps": 3},
    )


def make_manager(settings: Settings) -> SubSessionManager:
    """构造后台任务管理器。"""
    runner = TurnRunner(
        small_settings(settings),
        LlmGateway(settings),
        HookRegistry(),
        FakeEmbedder(),
        make_registry(),
        PermissionGate(),
    )
    return SubSessionManager(runner)


async def wait_finished(sub_session_id: int, limit: float = 30.0) -> str:
    """轮询后台任务状态，返回最终状态。"""
    deadline = asyncio.get_running_loop().time() + limit
    status = "running"
    while asyncio.get_running_loop().time() < deadline:
        row = await get_sub_session(sub_session_id)
        status = row.status
        if status != "running":
            break
        await asyncio.sleep(0.1)
    return status


async def test_sub_session_runs_clean_and_reports(
    fake_server: tuple[Settings, list[dict[str, object]]], engine_fixture: None, tmp_path
) -> None:
    """任务完成：子会话无人格绑定、请求无记忆段；父会话记 started/completed 事件。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    settings, captured = fake_server
    manager = make_manager(settings)
    parent = await create_session(str(tmp_path), "父会话", "standard")
    row = await create_sub_session(parent, "跑个任务", "hi")
    manager.start(parent, row.id, row.child_session_id, row.title, row.task)
    status = await wait_finished(row.id)
    assert status == "completed"

    detail = await get_sub_session(row.id)
    assert detail.result
    child = await get_session(row.child_session_id)
    assert child.kind == "sub"
    assert child.persona_version_id is None
    assert child.parent_id == parent

    events = await replay_events(parent)
    types = [event.type for event in events]
    assert "subsession/started" in types
    assert "subsession/completed" in types

    child_request = next(body for body in captured if "后台任务的执行器" in str(body.get("messages")))
    contents = [str(message.get("content")) for message in child_request["messages"]]  # type: ignore[union-attr]
    assert not any("记忆资料" in text for text in contents)
    assert not any("老板" in text for text in contents)


async def test_sub_session_auto_denies_approval(
    fake_server: tuple[Settings, list[dict[str, object]]], engine_fixture: None, tmp_path
) -> None:
    """审批自动拒绝：后台任务不弹审批卡，拒绝信息进事件与结果。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    settings, _ = fake_server
    manager = make_manager(settings)
    parent = await create_session(str(tmp_path), "父会话", "loose")
    row = await create_sub_session(parent, "删文件", "tool_delete")
    manager.start(parent, row.id, row.child_session_id, row.title, row.task)
    status = await wait_finished(row.id)
    assert status in {"completed", "failed"}

    child_events = await replay_events(row.child_session_id)
    denied = [
        event for event in child_events if event.type == "tool.result" and "自动拒绝" in str(event.payload.get("error"))
    ]
    assert denied, "缺少审批自动拒绝的 tool.result"


async def test_sub_session_rejects_nesting(engine_fixture: None, tmp_path) -> None:
    """子会话不能再开启子会话。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    parent = await create_session(str(tmp_path), "父会话")
    row = await create_sub_session(parent, "任务一", "hi")
    with pytest.raises(ApiError, match="不能再开启"):
        await create_sub_session(row.child_session_id, "任务二", "hi")


async def test_restart_marks_running_failed(engine_fixture: None, tmp_path) -> None:
    """重启清理：running 任务标记失败（进程不在了，不可能续跑）。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    parent = await create_session(str(tmp_path), "父会话")
    row = await create_sub_session(parent, "悬挂任务", "hi")
    assert (await get_sub_session(row.id)).status == "running"
    assert await mark_stale_running() >= 1
    detail = await get_sub_session(row.id)
    assert detail.status == "failed"
    assert detail.result is not None


async def test_sub_session_api(client: AsyncClient, engine_fixture: None) -> None:
    """接口：提交后台任务、列表可查、最终状态落库。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    created = (await client.post("/sessions", json={"work_dir": "D:\\tmp", "title": "后台"})).json()
    sid = int(created["data"]["id"])
    submitted = (await client.post(f"/sessions/{sid}/subsessions", json={"task": "hi"})).json()
    assert submitted["code"] == 0
    sub_id = int(submitted["data"]["id"])
    for _ in range(300):
        rows = (await client.get(f"/sessions/{sid}/subsessions")).json()["data"]["subsessions"]
        assert rows and rows[0]["id"] == str(sub_id)
        if rows[0]["status"] != "running":
            break
        await asyncio.sleep(0.1)
    detail = (await client.get(f"/subsessions/{sub_id}")).json()["data"]
    assert detail["status"] in {"completed", "failed", "running"}
    assert detail["child_session_id"]
