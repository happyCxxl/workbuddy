"""文件系统工具：直测执行与路径安全，以及循环接线的读/写/删裁决。"""

from pathlib import Path

import pytest
from conftest import FakeEmbedder, db_up

from lingxi.app.config import Settings
from lingxi.contract import ApiError
from lingxi.core.hooks import HookRegistry
from lingxi.core.log import replay_events
from lingxi.core.loop import TurnRunner
from lingxi.domain.llm.gateway import LlmGateway
from lingxi.domain.permission.gate import PermissionGate
from lingxi.domain.session.service import create_session
from lingxi.domain.tools.fs import register_fs_tools
from lingxi.domain.tools.registry import RISK_READ, RISK_WRITE, ToolContext, ToolRegistry


def make_registry() -> ToolRegistry:
    """标准工具注册表。"""
    registry = ToolRegistry()
    register_fs_tools(registry)
    return registry


async def test_fs_read_write_list(tmp_path: Path) -> None:
    """读/写/列直测：限定工作目录内正常工作。"""
    work_dir = str(tmp_path)
    (tmp_path / "hello.txt").write_text("你好，可乐", encoding="utf-8")
    registry = make_registry()
    read = registry.get("fs_read")
    assert read is not None and read.risk == RISK_READ
    assert await read.handler({"path": "hello.txt"}, ToolContext(work_dir)) == "你好，可乐"
    write = registry.get("fs_write")
    assert write is not None and write.risk == RISK_WRITE
    output = await write.handler({"path": "out.txt", "content": "可乐写入"}, ToolContext(work_dir))
    assert "已写入" in output
    assert (tmp_path / "out.txt").read_text(encoding="utf-8") == "可乐写入"
    list_tool = registry.get("fs_list")
    assert list_tool is not None
    listing = await list_tool.handler({"path": "."}, ToolContext(work_dir))
    assert "hello.txt" in listing
    assert "out.txt" in listing


async def test_fs_path_escape_rejected(tmp_path: Path) -> None:
    """越界路径拒绝：../ 逃逸工作目录报错。"""
    work_dir = str(tmp_path)
    secret = tmp_path.parent / "secret.txt"
    secret.write_text("机密", encoding="utf-8")
    registry = make_registry()
    read = registry.get("fs_read")
    assert read is not None
    with pytest.raises(ValueError, match="越界"):
        await read.handler({"path": "../secret.txt"}, ToolContext(work_dir))


async def test_fs_delete_removes_file(tmp_path: Path) -> None:
    """删除直测：文件被移除（高危裁决在循环层）。"""
    work_dir = str(tmp_path)
    (tmp_path / "gone.txt").write_text("待删", encoding="utf-8")
    registry = make_registry()
    delete = registry.get("fs_delete")
    assert delete is not None
    assert "已删除" in await delete.handler({"path": "gone.txt"}, ToolContext(work_dir))
    assert not (tmp_path / "gone.txt").exists()


async def test_loop_read_tool_end_to_end(
    fake_server: tuple[Settings, list[dict[str, object]]], engine_fixture: None, tmp_path: Path
) -> None:
    """循环接线：fs_read 真执行，tool.call/tool.result/tool.message 事件齐备。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    settings, _ = fake_server
    work_dir = str(tmp_path)
    (tmp_path / "hello.txt").write_text("你好，可乐", encoding="utf-8")
    small = Settings(
        server={},
        database={},
        tools={},
        memory=settings.memory.model_dump(),
        llm=settings.llm.model_dump(),
        permission={"preset": "standard"},
        loop={"input_token_budget": 100000, "context_budget_ratio": 0.8, "max_steps": 2},
    )
    runner = TurnRunner(
        small, LlmGateway(settings), HookRegistry(), FakeEmbedder(), make_registry(), PermissionGate("standard")
    )
    sid = await create_session(work_dir, "工具集成")
    with pytest.raises(ApiError):
        async for _event in runner.run(sid, "tool_read"):
            pass
    events = await replay_events(sid)
    types = [e.type for e in events]
    assert "tool.call" in types
    assert "tool.result" in types
    assert "tool/message" in types
    results = [e for e in events if e.type == "tool.result"]
    assert any("你好，可乐" in str(r.payload.get("result")) for r in results)


async def test_loop_write_asks_and_pauses(
    fake_server: tuple[Settings, list[dict[str, object]]], engine_fixture: None, tmp_path: Path
) -> None:
    """写工具触发询问：permission.required + 回合 pending_stop 停车，未落盘。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    settings, _ = fake_server
    work_dir = str(tmp_path)
    small = Settings(
        server={},
        database={},
        tools={},
        memory=settings.memory.model_dump(),
        llm=settings.llm.model_dump(),
        permission={"preset": "standard"},
        loop={"input_token_budget": 100000, "context_budget_ratio": 0.8, "max_steps": 2},
    )
    runner = TurnRunner(
        small, LlmGateway(settings), HookRegistry(), FakeEmbedder(), make_registry(), PermissionGate("standard")
    )
    sid = await create_session(work_dir, "写工具询问")
    events = [event async for event in runner.run(sid, "tool_write")]
    assert any(e.type == "permission.required" for e in events)
    assert events[-1].type == "turn.end"
    assert events[-1].payload.get("status") == "pending_stop"
    assert not (tmp_path / "out.txt").exists()
