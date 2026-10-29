"""回合执行：整轮事件序列、投影、钩子 fail-closed、预算压缩、步数超限。"""

import pytest
from conftest import FakeEmbedder, db_up

from lingxi.app.config import Settings
from lingxi.contract import ApiError, ErrorCode
from lingxi.core.events import TurnEvent
from lingxi.core.hooks import HookRegistry, TurnContext
from lingxi.core.log import EventType, append_event, replay_events
from lingxi.core.loop import TurnRunner
from lingxi.domain.llm.gateway import LlmGateway
from lingxi.domain.permission.gate import PermissionGate
from lingxi.domain.session.service import build_history, create_session
from lingxi.domain.tools.fs import register_fs_tools
from lingxi.domain.tools.registry import ToolRegistry


def make_registry() -> ToolRegistry:
    """标准工具注册表。"""
    registry = ToolRegistry()
    register_fs_tools(registry)
    return registry


def make_runner(settings: Settings, gateway: LlmGateway, hooks: HookRegistry) -> TurnRunner:
    """标准回合执行器。"""
    return TurnRunner(settings, gateway, hooks, FakeEmbedder(), make_registry(), PermissionGate())


async def collect(runner: TurnRunner, session_id: int, content: str) -> list[TurnEvent]:
    """跑一个回合并收集全部事件。"""
    return [event async for event in runner.run(session_id, content)]


async def test_assembly_prefix_is_cache_friendly(
    fake_server: tuple[Settings, list[dict[str, object]]], engine_fixture: None
) -> None:
    """连续两轮：静态前缀逐字节一致；动态段（运行时/记忆）在历史之后、本轮用户消息之前。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    settings, captured = fake_server
    runner = make_runner(settings, LlmGateway(settings), HookRegistry())
    sid = await create_session("D:\\tmp", "缓存布局")
    await collect(runner, sid, "hi")
    await collect(runner, sid, "hi")
    requests = [body for body in captured if isinstance(body.get("messages"), list)]
    assert len(requests) >= 2
    first = requests[-2]["messages"]
    second = requests[-1]["messages"]
    assert isinstance(first, list) and isinstance(second, list)
    # 前四条静态段（行为准则/能力/人格/来源声明）完全一致
    assert [str(m["content"]) for m in first[:4]] == [str(m["content"]) for m in second[:4]]
    assert all(str(m["role"]) == "system" for m in second[:4])
    # 历史排在静态段之后：第二条请求第 5 条起是首轮 user/assistant
    assert str(second[4]["role"]) == "user"
    contents = [str(m.get("content")) for m in second]
    user_index = next(index for index, text in enumerate(contents) if text == "hi")
    runtime_index = next(index for index, text in enumerate(contents) if text.startswith("【运行时上下文】"))
    assert runtime_index > user_index
    memory_indexes = [index for index, text in enumerate(contents) if "记忆资料" in text]
    assert all(index > user_index for index in memory_indexes)
    assert str(second[-1]["role"]) == "user"
    assert str(second[-1]["content"]) == "hi"


async def test_full_turn_events_and_projection(
    fake_server: tuple[Settings, list[dict[str, object]]], engine_fixture: None
) -> None:
    """整轮事件序列完整，历史投影还原对话；装配含能力段与来源声明段。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    settings, captured = fake_server
    runner = make_runner(settings, LlmGateway(settings), HookRegistry())
    sid = await create_session("D:\\tmp", "回合测试")
    yielded = await collect(runner, sid, "hi")
    yield_types = [e.type for e in yielded]
    assert yield_types[0] == "turn/start"
    assert yield_types[-1] == "turn.end"
    assert yield_types.count("text.delta") == 2
    events = await replay_events(sid)
    types = [e.type for e in events]
    assert types[0] == "turn/start"
    assert "user/message" in types
    assert types.count("text.delta") == 2
    assert "assistant/message" in types
    assert types[-1] == "turn.end"
    history = await build_history(sid)
    assert [m.role for m in history] == ["user", "assistant"]
    assert history[1].content == "你好"
    sent_messages = [m for c in captured if isinstance(c.get("messages"), list) for m in c["messages"]]
    assert any("【能力与规则】" in str(m.get("content")) for m in sent_messages)
    assert any("应用注入的上下文" in str(m.get("content")) for m in sent_messages)


async def test_hook_fail_closed(fake_server: tuple[Settings, list[dict[str, object]]], engine_fixture: None) -> None:
    """pre-turrn 钩子抛错：回合中止并补 error 与 turn/end 事件。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    settings, _ = fake_server
    hooks = HookRegistry()

    async def boom(_ctx: TurnContext) -> None:
        raise ApiError(ErrorCode.INVALID_ARGUMENT, "钩子失败")

    hooks.register("pre_turn", boom)
    runner = make_runner(settings, LlmGateway(settings), hooks)
    sid = await create_session("D:\\tmp", "钩子测试")
    with pytest.raises(ApiError) as exc_info:
        await collect(runner, sid, "hi")
    assert exc_info.value.error.code == 60001
    events = await replay_events(sid)
    assert events[0].type == "error"
    assert events[-1].type == "turn.end"


async def test_budget_overflow_triggers_compaction(
    fake_server: tuple[Settings, list[dict[str, object]]], engine_fixture: None
) -> None:
    """历史超压缩阈值：追加 compaction 事件并截断投影（阈值 = 预算 × ratio，独立于提示长度）。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    settings, _ = fake_server
    small = Settings(
        server={},
        database={},
        tools={},
        llm=settings.llm.model_dump(),
        loop={"input_token_budget": 100000, "context_budget_ratio": 0.01, "max_steps": 25},
    )
    runner = make_runner(small, LlmGateway(settings), HookRegistry())
    sid = await create_session("D:\\tmp", "压缩测试")
    for i in range(80):
        await append_event(sid, EventType.USER_MESSAGE, {"content": f"用户第{i}条消息，内容长度用来占预算。"})
        await append_event(sid, EventType.ASSISTANT_MESSAGE, {"content": f"助手第{i}条回复，内容长度用来占预算。"})
    await collect(runner, sid, "hi")
    events = await replay_events(sid)
    assert any(e.type == "compaction" for e in events)
    history = await build_history(sid)
    assert len(history) < 162


async def test_max_steps_aborts_turn(
    fake_server: tuple[Settings, list[dict[str, object]]], engine_fixture: None
) -> None:
    """持续工具调用耗尽步数：报 60202 并落 error/turn/end。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    settings, _ = fake_server
    small = Settings(
        server={},
        database={},
        tools={},
        llm=settings.llm.model_dump(),
        loop={"input_token_budget": 100000, "context_budget_ratio": 0.8, "max_steps": 2},
    )
    runner = make_runner(small, LlmGateway(settings), HookRegistry())
    sid = await create_session("D:\\tmp", "步数测试")
    with pytest.raises(ApiError) as exc_info:
        await collect(runner, sid, "tool")
    assert exc_info.value.error.code == 60202
    events = await replay_events(sid)
    types = [e.type for e in events]
    assert types.count("tool.call") == 2
    assert "error" in types
    assert types[-1] == "turn.end"


async def test_stop_flag_does_not_leak_into_next_turn(
    fake_server: tuple[Settings, list[dict[str, object]]], engine_fixture: None
) -> None:
    """停车标记只属于当轮：被停车后，下一轮照常执行（断线/取消也不能把会话卡死）。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    settings, _ = fake_server
    runner = make_runner(settings, LlmGateway(settings), HookRegistry())
    sid = await create_session("D:\\tmp", "停车回收")
    runner.request_stop(sid)
    stopped = await collect(runner, sid, "hi")
    assert stopped[-1].payload["status"] == "pending_stop"
    resumed = await collect(runner, sid, "hi")
    assert resumed[-1].payload["status"] == "completed"


async def test_post_hook_failure_keeps_turn_completed(
    fake_server: tuple[Settings, list[dict[str, object]]], engine_fixture: None
) -> None:
    """后置钩子失败不影响已完成的回合：turn.end 仍为 completed，异常只记日志。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    settings, _ = fake_server
    hooks = HookRegistry()

    async def boom(_ctx: TurnContext) -> None:
        raise RuntimeError("记忆服务不可用")

    hooks.register("post_execute", boom)
    runner = make_runner(settings, LlmGateway(settings), hooks)
    sid = await create_session("D:\\tmp", "钩子失败")
    events = await collect(runner, sid, "hi")
    assert events[-1].type == "turn.end"
    assert events[-1].payload["status"] == "completed"
