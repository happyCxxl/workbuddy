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
from lingxi.domain.session.service import build_history, create_session


async def collect(runner: TurnRunner, session_id: int, content: str) -> list[TurnEvent]:
    """跑一个回合并收集全部事件。"""
    return [event async for event in runner.run(session_id, content)]


async def test_full_turn_events_and_projection(
    fake_server: tuple[Settings, list[dict[str, object]]], engine_fixture: None
) -> None:
    """整轮事件序列完整，历史投影还原对话。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    settings, _ = fake_server
    runner = TurnRunner(settings, LlmGateway(settings), HookRegistry(), FakeEmbedder())
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


async def test_hook_fail_closed(fake_server: tuple[Settings, list[dict[str, object]]], engine_fixture: None) -> None:
    """pre-turrn 钩子抛错：回合中止并补 error 与 turn/end 事件。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    settings, _ = fake_server
    hooks = HookRegistry()

    async def boom(_ctx: TurnContext) -> None:
        raise ApiError(ErrorCode.INVALID_ARGUMENT, "钩子失败")

    hooks.register("pre_turn", boom)
    runner = TurnRunner(settings, LlmGateway(settings), hooks, FakeEmbedder())
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
    """历史超预算 80%：追加 compaction 事件并截断投影。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    settings, _ = fake_server
    small = Settings(
        server={},
        database={},
        tools={},
        llm=settings.llm.model_dump(),
        loop={"input_token_budget": 1000, "context_budget_ratio": 0.8, "max_steps": 25},
    )
    runner = TurnRunner(small, LlmGateway(settings), HookRegistry(), FakeEmbedder())
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
    runner = TurnRunner(small, LlmGateway(settings), HookRegistry(), FakeEmbedder())
    sid = await create_session("D:\\tmp", "步数测试")
    with pytest.raises(ApiError) as exc_info:
        await collect(runner, sid, "tool")
    assert exc_info.value.error.code == 60202
    events = await replay_events(sid)
    types = [e.type for e in events]
    assert types.count("tool.call") == 2
    assert "error" in types
    assert types[-1] == "turn.end"
