"""共享测试基建：本地 fake OpenAI 兼容服务器（禁真实模型调用）。"""

import asyncio
import json
import threading
from collections.abc import AsyncIterator

import pytest
import uvicorn
from fastapi import FastAPI, Request, Response
from fastapi.responses import JSONResponse, StreamingResponse

from lingxi.app.config import Settings

VALID_JSON = '{"name": "可乐", "score": 1}'
INVALID_JSON = "not-json"


def _chunk(
    content: str = "", finish_reason: str | None = None, usage: dict[str, int] | None = None
) -> dict[str, object]:
    """构造一条 OpenAI 兼容 chunk。"""
    body: dict[str, object] = {
        "id": "fake",
        "object": "chat.completion.chunk",
        "created": 1,
        "model": "deepseek-flash",
        "choices": [{"index": 0, "delta": {"content": content} if content else {}, "finish_reason": finish_reason}],
    }
    if usage is not None:
        body["usage"] = usage
    return body


def make_fake_app(captured: list[dict[str, object]]) -> FastAPI:
    """fake 服务器：按提示词标记分支响应，并记录全部请求体。"""

    app = FastAPI()
    json_object_calls: list[int] = [0]

    @app.post("/responses")
    async def responses(request: Request) -> Response:
        body = await request.json()
        captured.append(body)
        prompt = str(body.get("input", ""))
        if any(marker in prompt for marker in ("no_strict", "bad_once", "always_bad")):
            return JSONResponse(status_code=400, content={"error": {"message": "unsupported"}})
        return JSONResponse(
            content={
                "id": "resp_fake",
                "object": "response",
                "created": 1,
                "model": "deepseek-flash",
                "status": "completed",
                "output": [
                    {
                        "type": "message",
                        "role": "assistant",
                        "content": [{"type": "output_text", "text": VALID_JSON}],
                    }
                ],
            }
        )

    @app.post("/chat/completions")
    async def chat_completions(request: Request) -> Response:
        body = await request.json()
        captured.append(body)
        last = body.get("messages", [{}])[-1]
        content = str(last.get("content", ""))

        if body.get("response_format") == {"type": "json_object"}:
            json_object_calls[0] += 1
            if "always_bad" in content or ("bad_once" in content and json_object_calls[0] == 1):
                payload = INVALID_JSON
            else:
                payload = VALID_JSON
            return JSONResponse(
                content={
                    "id": "fake",
                    "object": "chat.completion",
                    "created": 1,
                    "model": "deepseek-flash",
                    "choices": [
                        {"index": 0, "message": {"role": "assistant", "content": payload}, "finish_reason": "stop"}
                    ],
                }
            )

        if content == "tool":

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

        if content == "reason":

            async def reason_gen() -> AsyncIterator[str]:
                for piece in ("在想", "你"):
                    data = _chunk()
                    data["choices"] = [{"index": 0, "delta": {"reasoning_content": piece}, "finish_reason": None}]
                    yield f"data: {json.dumps(data, ensure_ascii=False)}\n\n"
                yield f"data: {json.dumps(_chunk(finish_reason='stop', usage={'prompt_tokens': 5, 'completion_tokens': 2, 'total_tokens': 7}), ensure_ascii=False)}\n\n"
                yield "data: [DONE]\n\n"

            return StreamingResponse(reason_gen(), media_type="text/event-stream")

        if content == "usage_standard":

            async def usage_gen() -> AsyncIterator[str]:
                yield f"data: {json.dumps(_chunk(content='你'), ensure_ascii=False)}\n\n"
                yield f"data: {json.dumps(_chunk(content='好', finish_reason='stop'), ensure_ascii=False)}\n\n"
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
