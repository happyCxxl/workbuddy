"""对话通道适配器测试：起本地 fake OpenAI 兼容服务器，禁真实模型调用。"""

import asyncio
import json
import threading
from collections.abc import AsyncIterator

import pytest
import uvicorn
from fastapi import FastAPI, Request, Response
from fastapi.responses import StreamingResponse

from lingxi.app.config import Settings
from lingxi.domain.llm.chat import DeepSeekAdapter
from lingxi.domain.ports import ChatMessage, ChatRequest


def _chunk(
    content: str = "", finish_reason: str | None = None, usage: dict[str, int] | None = None
) -> dict[str, object]:
    """构造一条 OpenAI 兼容 chunk。"""
    body: dict[str, object] = {
        "id": "fake",
        "object": "chat.completion.chunk",
        "created": 1,
        "model": "deepseek-chat",
        "choices": [{"index": 0, "delta": {"content": content} if content else {}, "finish_reason": finish_reason}],
    }
    if usage is not None:
        body["usage"] = usage
    return body


def make_fake_app(captured: list[dict[str, object]]) -> FastAPI:
    """构建 fake 服务器：按用户消息内容分支响应，并记录全部请求体。"""

    app = FastAPI()

    @app.post("/chat/completions")
    async def chat_completions(request: Request) -> Response:
        body = await request.json()
        captured.append(body)
        last = body.get("messages", [{}])[-1]
        mode = last.get("content", "")

        if mode == "tool":

            async def tool_gen() -> AsyncIterator[str]:
                data = _chunk()
                data["choices"] = [
                    {
                        "index": 0,
                        "delta": {
                            "tool_calls": [
                                {
                                    "index": 0,
                                    "id": "call_1",
                                    "type": "function",
                                    "function": {"name": "fs_read", "arguments": ""},
                                }
                            ]
                        },
                        "finish_reason": None,
                    }
                ]
                yield f"data: {json.dumps(data, ensure_ascii=False)}\n\n"
                data = _chunk(
                    finish_reason="stop", usage={"prompt_tokens": 7, "completion_tokens": 3, "total_tokens": 10}
                )
                data["choices"] = [
                    {
                        "index": 0,
                        "delta": {"tool_calls": [{"index": 0, "function": {"arguments": '{"path": "x"}'}}]},
                        "finish_reason": "stop",
                    }
                ]
                yield f"data: {json.dumps(data, ensure_ascii=False)}\n\n"
                yield "data: [DONE]\n\n"

            return StreamingResponse(tool_gen(), media_type="text/event-stream")

        if mode == "reason":

            async def reason_gen() -> AsyncIterator[str]:
                for piece in ("在想", "你"):
                    data = _chunk()
                    data["choices"] = [{"index": 0, "delta": {"reasoning_content": piece}, "finish_reason": None}]
                    yield f"data: {json.dumps(data, ensure_ascii=False)}\n\n"
                yield f"data: {json.dumps(_chunk(finish_reason='stop', usage={'prompt_tokens': 5, 'completion_tokens': 2, 'total_tokens': 7}), ensure_ascii=False)}\n\n"
                yield "data: [DONE]\n\n"

            return StreamingResponse(reason_gen(), media_type="text/event-stream")

        if mode == "usage_standard":

            async def usage_gen() -> AsyncIterator[str]:
                yield f"data: {json.dumps(_chunk(content='你'), ensure_ascii=False)}\n\n"
                yield f"data: {json.dumps(_chunk(content='好', finish_reason='stop'), ensure_ascii=False)}\n\n"
                # 标准独立终块：空 choices + usage
                data = _chunk()
                data["choices"] = []
                data["usage"] = {"prompt_tokens": 9, "completion_tokens": 4, "total_tokens": 13}
                yield f"data: {json.dumps(data, ensure_ascii=False)}\n\n"
                yield "data: [DONE]\n\n"

            return StreamingResponse(usage_gen(), media_type="text/event-stream")

        async def text_gen() -> AsyncIterator[str]:
            # DeepSeek 形态：usage 挂在最后一个内容 chunk 上
            yield f"data: {json.dumps(_chunk(content='你'), ensure_ascii=False)}\n\n"
            yield f"data: {json.dumps(_chunk(content='好', finish_reason='stop', usage={'prompt_tokens': 9, 'completion_tokens': 4, 'total_tokens': 13}), ensure_ascii=False)}\n\n"
            yield "data: [DONE]\n\n"

        return StreamingResponse(text_gen(), media_type="text/event-stream")

    return app


@pytest.fixture
async def fake_server() -> AsyncIterator[tuple[Settings, list[dict[str, object]]]]:
    """启动本地 fake 服务器并回传指向它的 Settings 与请求捕获列表。"""
    captured: list[dict[str, object]] = []
    config = uvicorn.Config(make_fake_app(captured), host="127.0.0.1", port=0, log_level="warning")
    server = uvicorn.Server(config)
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    while not server.started:  # noqa: ASYNC110 线程内服务启动无事件可等
        await asyncio.sleep(0.05)
    port = server.servers[0].sockets[0].getsockname()[1]
    settings = Settings(llm={"base_url": f"http://127.0.0.1:{port}", "api_key": "fake"})
    try:
        yield settings, captured
    finally:
        server.should_exit = True
        thread.join(timeout=5)


async def collect(adapter: DeepSeekAdapter, messages: list[ChatMessage]) -> list[object]:
    """跑一次流式调用并收集全部 chunk。"""
    return [chunk async for chunk in adapter.stream(ChatRequest(messages=messages))]


async def test_stream_text_and_usage_last_chunk_form(fake_server: tuple[Settings, list[dict[str, object]]]) -> None:
    """DeepSeek 形态：usage 挂最后内容 chunk，归一化为末尾 usage 块。"""
    settings, _ = fake_server
    chunks = await collect(DeepSeekAdapter(settings), [ChatMessage(role="user", content="hi")])
    texts = [c.text for c in chunks if c.kind == "text"]
    assert texts == ["你", "好"]
    assert chunks[-1].kind == "usage"
    assert chunks[-1].usage is not None
    assert (chunks[-1].usage.prompt_tokens, chunks[-1].usage.completion_tokens) == (9, 4)


async def test_usage_standard_form(fake_server: tuple[Settings, list[dict[str, object]]]) -> None:
    """标准形态：usage 在独立空 choices 终块，同样归一为末尾 usage 块。"""
    settings, _ = fake_server
    chunks = await collect(DeepSeekAdapter(settings), [ChatMessage(role="user", content="usage_standard")])
    assert chunks[-1].kind == "usage"
    assert chunks[-1].usage is not None
    assert (chunks[-1].usage.prompt_tokens, chunks[-1].usage.completion_tokens) == (9, 4)


async def test_tool_call_passthrough(fake_server: tuple[Settings, list[dict[str, object]]]) -> None:
    """工具调用跨 chunk 片段合并为完整 ToolCall。"""
    settings, _ = fake_server
    chunks = await collect(DeepSeekAdapter(settings), [ChatMessage(role="user", content="tool")])
    tool_chunks = [c for c in chunks if c.kind == "tool_calls"]
    assert len(tool_chunks) == 1
    assert len(tool_chunks[0].tool_calls) == 1
    call = tool_chunks[0].tool_calls[0]
    assert call.id == "call_1"
    assert call.name == "fs_read"
    assert call.arguments == '{"path": "x"}'


async def test_reasoning_streamed_and_echoed_back(fake_server: tuple[Settings, list[dict[str, object]]]) -> None:
    """思考内容流式产出；assistant 消息携带的 reasoning_content 原样回传。"""
    settings, captured = fake_server
    messages = [
        ChatMessage(role="user", content="上一轮问题"),
        ChatMessage(role="assistant", content="上一轮回答", reasoning_content="上次的思考"),
        ChatMessage(role="user", content="reason"),
    ]
    chunks = await collect(DeepSeekAdapter(settings), messages)
    thinking = [c.text for c in chunks if c.kind == "thinking"]
    assert thinking == ["在想", "你"]
    sent = captured[-1]["messages"]
    assert sent[1]["reasoning_content"] == "上次的思考"
