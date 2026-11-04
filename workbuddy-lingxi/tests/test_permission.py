"""权限：三档预设、高危恒询问与审批停车/一次性恢复。"""

from collections.abc import AsyncIterator
from pathlib import Path

import pytest
from conftest import FakeEmbedder, db_up

from lingxi.app.config import Settings
from lingxi.core.hooks import HookRegistry
from lingxi.core.loop import TurnEvent, TurnRunner
from lingxi.domain.llm.gateway import LlmGateway
from lingxi.domain.permission.gate import PermissionGate
from lingxi.domain.ports import ChatRequest, StreamChunk, ToolCall, Usage
from lingxi.domain.session.service import build_history, create_session
from lingxi.domain.tools.fs import register_fs_tools
from lingxi.domain.tools.registry import ToolRegistry


def make_registry() -> ToolRegistry:
    """标准工具注册表。"""
    registry = ToolRegistry()
    register_fs_tools(registry)
    return registry


def small_settings(settings: Settings, preset: str) -> Settings:
    """以 fake 的 llm/memory 构造小循环配置。"""
    return Settings(
        server={},
        database={},
        tools={},
        memory=settings.memory.model_dump(),
        llm=settings.llm.model_dump(),
        permission={"preset": preset},
        loop={"input_token_budget": 100000, "context_budget_ratio": 0.8, "max_steps": 25},
    )


def make_runner(settings: Settings, preset: str) -> TurnRunner:
    """构造带假嵌入器的回合执行器。"""
    return TurnRunner(
        small_settings(settings, preset),
        LlmGateway(settings),
        HookRegistry(),
        FakeEmbedder(),
        make_registry(),
        PermissionGate(),
    )


async def run_until_permission(
    runner: TurnRunner, session_id: int, content: str
) -> tuple[AsyncIterator[TurnEvent], list[TurnEvent], str]:
    """驱动回合直到审批事件；返回仍在等待的生成器、已收事件与 request_id。"""
    stream = runner.run(session_id, content)
    events: list[TurnEvent] = []
    async for event in stream:
        events.append(event)
        if event.type == "permission.required":
            return stream, events, str(event.payload["request_id"])
    raise AssertionError("回合未进入审批")


@pytest.mark.usefixtures("engine_fixture")
async def test_preset_read_only_denies_write() -> None:
    """仅可查看档：写工具直接拒绝，读工具放行。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    gate = PermissionGate()
    write = make_registry().get("fs_write")
    read = make_registry().get("fs_read")
    delete = make_registry().get("fs_delete")
    assert write is not None and read is not None and delete is not None
    denied = await gate.decide(write, "D:\\tmp", {"path": "new.txt", "content": "x"}, "read-only")
    assert denied.verdict == "deny"
    removed = await gate.decide(delete, "D:\\tmp", {"path": "x.txt"}, "read-only")
    assert removed.verdict == "deny"
    allowed = await gate.decide(read, "D:\\tmp", {"path": "x.txt"}, "read-only")
    assert allowed.verdict == "allow"


async def test_preset_workspace_write_allows_write() -> None:
    """工作区内修改档：工作区内写工具（非覆盖）放行。"""
    gate = PermissionGate()
    tool = make_registry().get("fs_write")
    assert tool is not None
    decision = await gate.decide(tool, "D:\\tmp", {"path": "new.txt", "content": "x"}, "workspace-write")
    assert decision.verdict == "allow"


async def test_high_risk_always_asks_even_full_access() -> None:
    """高危恒询问：完全权限档下删除也问。"""
    gate = PermissionGate()
    tool = make_registry().get("fs_delete")
    assert tool is not None
    decision = await gate.decide(tool, "D:\\tmp", {"path": "x.txt"}, "full-access")
    assert decision.verdict == "ask"


@pytest.mark.usefixtures("engine_fixture")
async def test_ask_pause_and_resume_allowed_once(
    fake_server: tuple[Settings, list[dict[str, object]]], tmp_path: Path
) -> None:
    """询问停车 → allowed-once 恢复：permission.required 与 tool.result 成对、工具真执行。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    settings, _ = fake_server
    work_dir = str(tmp_path)
    target = tmp_path / "hello.txt"
    target.write_text("待删除", encoding="utf-8")
    runner = make_runner(settings, "workspace-write")
    sid = await create_session(work_dir, "审批恢复", "workspace-write")
    stream, events, request_id = await run_until_permission(runner, sid, "tool_delete")
    assert events[-1].type == "permission.required"
    assert target.exists()
    assert await runner.resolve_approval(sid, request_id, "allowed-once") is True
    async for event in stream:
        events.append(event)
        if event.type == "tool.result":
            break
    assert any(e.type == "tool.result" and "已删除" in str(e.payload.get("result")) for e in events)
    assert not target.exists()


@pytest.mark.usefixtures("engine_fixture")
async def test_parallel_batch_survives_approval(
    fake_server: tuple[Settings, list[dict[str, object]]], tmp_path: Path
) -> None:
    """审批挂起一个调用后，恢复时同批剩余调用仍执行并各自产生 tool 结果。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    settings, _ = fake_server
    target = tmp_path / "hello.txt"
    target.write_text("待删除", encoding="utf-8")

    class BatchGateway:
        """首轮返回 删除 + 列目录 两个调用，第二轮返回文本。"""

        def __init__(self) -> None:
            self.requests: list[ChatRequest] = []

        async def stream(self, request: ChatRequest):
            """按请求序号返回工具批次或最终文本。"""
            self.requests.append(request)
            if len(self.requests) == 1:
                yield StreamChunk(
                    kind="tool_calls",
                    tool_calls=[
                        ToolCall(
                            id="call_delete",
                            name="fs_delete",
                            arguments='{"path": "hello.txt"}',
                        ),
                        ToolCall(id="call_list", name="fs_list", arguments='{"path": "."}'),
                    ],
                )
                yield StreamChunk(
                    kind="usage",
                    tool_calls=[],
                    usage=Usage(prompt_tokens=10, completion_tokens=2),
                )
                return
            yield StreamChunk(kind="text", text="处理完成", tool_calls=[])
            yield StreamChunk(
                kind="usage",
                tool_calls=[],
                usage=Usage(prompt_tokens=20, completion_tokens=3),
            )

    gateway = BatchGateway()
    runner = TurnRunner(
        small_settings(settings, "workspace-write"),
        gateway,
        HookRegistry(),
        FakeEmbedder(),
        make_registry(),
        PermissionGate(),
    )
    sid = await create_session(str(tmp_path), "并行审批", "workspace-write")
    stream, events, request_id = await run_until_permission(runner, sid, "删文件再列目录")
    assert events[-1].type == "permission.required"
    assert await runner.resolve_approval(sid, request_id, "allowed-once") is True
    async for event in stream:
        events.append(event)

    results = [e for e in events if e.type == "tool.result"]
    assert {e.payload.get("tool_call_id") for e in results} == {"call_delete", "call_list"}
    assert not target.exists()
    second = gateway.requests[1]
    tool_messages = [message for message in second.messages if message.role == "tool"]
    assert {message.tool_call_id for message in tool_messages} == {"call_delete", "call_list"}


@pytest.mark.usefixtures("engine_fixture")
async def test_cancel_pending_writes_skipped_results(
    fake_server: tuple[Settings, list[dict[str, object]]], tmp_path: Path
) -> None:
    """审批挂起时停止：未执行调用补错误结果，历史仍保持配对。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    settings, _ = fake_server

    class BatchGateway:
        """首轮返回一个待审批删除调用。"""

        async def stream(self, request: ChatRequest):
            """只返回一次工具调用。"""
            yield StreamChunk(
                kind="tool_calls",
                tool_calls=[ToolCall(id="call_delete", name="fs_delete", arguments='{"path": "missing.txt"}')],
            )

    runner = TurnRunner(
        small_settings(settings, "workspace-write"),
        BatchGateway(),
        HookRegistry(),
        FakeEmbedder(),
        make_registry(),
        PermissionGate(),
    )
    sid = await create_session(str(tmp_path), "取消审批", "workspace-write")
    stream, _, _ = await run_until_permission(runner, sid, "删文件")

    assert runner.request_stop(sid) is True
    async for _event in stream:
        pass
    history = await build_history(sid)
    tool_messages = [message for message in history if message.role == "tool"]
    assert [message.tool_call_id for message in tool_messages] == ["call_delete"]


@pytest.mark.usefixtures("engine_fixture")
async def test_resume_rejected(fake_server: tuple[Settings, list[dict[str, object]]], tmp_path: Path) -> None:
    """裁决拒绝：落拒绝结果，不执行。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    settings, _ = fake_server
    work_dir = str(tmp_path)
    target = tmp_path / "hello.txt"
    target.write_text("保留", encoding="utf-8")
    runner = make_runner(settings, "workspace-write")
    sid = await create_session(work_dir, "裁决拒绝", "workspace-write")
    stream, events, request_id = await run_until_permission(runner, sid, "tool_delete")
    assert await runner.resolve_approval(sid, request_id, "rejected") is True
    async for event in stream:
        events.append(event)
        if event.type == "tool.result":
            break
    assert any(e.type == "tool.result" and "用户拒绝" in str(e.payload.get("error")) for e in events)
    assert target.exists()


@pytest.mark.usefixtures("engine_fixture")
async def test_resume_unknown_request(fake_server: tuple[Settings, list[dict[str, object]]], tmp_path: Path) -> None:
    """不存在的审批请求：报 60002。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    settings, _ = fake_server
    runner = make_runner(settings, "workspace-write")
    sid = await create_session(str(tmp_path), "未知审批", "workspace-write")
    assert await runner.resolve_approval(sid, "no-such-id", "allowed-once") is False
