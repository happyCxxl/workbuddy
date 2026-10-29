"""模型出口门面测试：重试、降级、全败与调用明细落库。"""

import uuid
from collections.abc import AsyncIterator

import pytest
from conftest import db_up
from pydantic import BaseModel
from sqlalchemy import select

from lingxi.app.config import Settings
from lingxi.contract import REQUEST_ID, ApiError
from lingxi.db.engine import session_scope
from lingxi.db.models import ModelCall
from lingxi.domain.llm.gateway import LlmGateway
from lingxi.domain.ports import ChatMessage, ChatRequest, StreamChunk


class Sample(BaseModel):
    """结构化测试目标模型。"""

    name: str
    score: int


async def collect_stream(gateway: LlmGateway, content: str) -> list[object]:
    """跑一次流式调用并收集全部 chunk。"""
    request = ChatRequest(messages=[ChatMessage(role="user", content=content, tool_calls=[])], tools=[])
    return [chunk async for chunk in gateway.stream(request)]


async def test_stream_retry_then_success(fake_server: tuple[Settings, list[dict[str, object]]]) -> None:
    """5xx 重试两次后成功：同候选内不降级。"""
    settings, captured = fake_server
    chunks = await collect_stream(LlmGateway(settings), "fail_twice")
    assert chunks[-1].kind == "usage"
    attempts = [c for c in captured if c.get("model") == "deepseek-flash"]
    assert len(attempts) == 3


async def test_stream_degrades_to_pro(fake_server: tuple[Settings, list[dict[str, object]]]) -> None:
    """flash 持续失败 → 降级 pro 成功。"""
    settings, captured = fake_server
    chunks = await collect_stream(LlmGateway(settings), "fail_always")
    assert chunks[-1].kind == "usage"
    assert any(c.get("model") == "deepseek-v4-pro" for c in captured)


async def test_stream_all_candidates_fail(fake_server: tuple[Settings, list[dict[str, object]]]) -> None:
    """两候选全败 → 报 60201。"""
    settings, _ = fake_server
    with pytest.raises(ApiError) as exc_info:
        await collect_stream(LlmGateway(settings), "fail_both")
    assert exc_info.value.error.code == 60201


async def test_structured_degrades_to_flash(fake_server: tuple[Settings, list[dict[str, object]]]) -> None:
    """结构化任务 pro 持续失败 → 降级 flash 成功。"""
    settings, captured = fake_server
    result = await LlmGateway(settings).structured("fail_pro 请输出", Sample)
    assert result == Sample(name="可乐", score=1)
    assert any(c.get("model") == "deepseek-flash" for c in captured)


async def test_model_call_recorded(fake_server: tuple[Settings, list[dict[str, object]]], engine_fixture: None) -> None:
    """调用明细落库：成功记用量，失败记错误码。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    settings, _ = fake_server
    rid = f"gw-record-{uuid.uuid4().hex[:8]}"
    REQUEST_ID.set(rid)
    gateway = LlmGateway(settings)
    await collect_stream(gateway, "hi")
    with pytest.raises(ApiError):
        await collect_stream(gateway, "fail_both")
    async with session_scope() as s:
        rows = list((await s.scalars(select(ModelCall).where(ModelCall.request_id == rid))).all())
    ok_rows = [r for r in rows if r.error_code is None]
    err_rows = [r for r in rows if r.error_code is not None]
    assert len(ok_rows) == 1
    assert ok_rows[0].model == "deepseek-flash"
    assert ok_rows[0].prompt_tokens == 9
    assert ok_rows[0].completion_tokens == 4
    assert err_rows


class _DropAfterText:
    """先吐一段正文再断开：模拟上游流式中断（如连接停滞被超时切断）。"""

    def __init__(self) -> None:
        self.calls = 0

    async def stream(self, request: ChatRequest) -> AsyncIterator[StreamChunk]:
        self.calls += 1
        yield StreamChunk(kind="text", text="半句", tool_calls=[])
        raise RuntimeError("stream dropped")


async def test_stream_partial_output_not_retried(engine_fixture: None, monkeypatch) -> None:
    """已输出正文后断流：不重试、直接报 60201（重试会把正文重复一遍）。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    settings = Settings(llm={"api_key": "fake", "base_url": "http://127.0.0.1:1"})
    gateway = LlmGateway(settings)
    stub = _DropAfterText()
    monkeypatch.setattr(gateway, "_flash", stub)
    with pytest.raises(ApiError) as exc_info:
        await collect_stream(gateway, "hi")
    assert exc_info.value.error.code == 60201
    assert stub.calls == 1
