"""共享测试基建：本地 fake OpenAI 兼容服务器（禁真实模型调用）。"""

import asyncio
import hashlib
import json
import math
import random
import re
import threading
from collections.abc import AsyncIterator

import pytest
import uvicorn
from fastapi import FastAPI, Request, Response
from fastapi.responses import JSONResponse, StreamingResponse
from httpx2 import ASGITransport, AsyncClient
from sqlalchemy import text

from lingxi.app.config import Settings
from lingxi.app.main import create_app
from lingxi.db.engine import dispose_engine, init_engine, session_scope
from lingxi.db.models import AiSession
from lingxi.db.repo.memory_repo import MemoryRepo
from lingxi.domain.memory.embedding import Embedder


class FakeEmbedder(Embedder):
    """确定性假向量：测试不调真实 embedding API；同文本稳定，不同文本近似正交。"""

    async def embed(self, text: str) -> list[float]:
        rng = random.Random(hashlib.sha256(text.encode("utf-8")).digest())
        vector = [rng.gauss(0.0, 1.0) for _ in range(1024)]
        norm = math.sqrt(sum(value * value for value in vector))
        return [value / norm for value in vector]


VALID_JSON = '{"name": "可乐", "score": 1}'
INVALID_JSON = "not-json"


def _responses_json(payload: str | dict[str, object]) -> JSONResponse:
    """Responses 协议响应：output_text 携带给定文本。"""
    text_content = payload if isinstance(payload, str) else json.dumps(payload, ensure_ascii=False)
    return JSONResponse(
        content={
            "id": "resp_fake",
            "object": "response",
            "created": 1,
            "model": "deepseek-flash",
            "status": "completed",
            "output": [
                {"type": "message", "role": "assistant", "content": [{"type": "output_text", "text": text_content}]}
            ],
        }
    )


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
    fail_counts: dict[str, int] = {}

    @app.post("/responses")
    async def responses(request: Request) -> Response:
        body = await request.json()
        captured.append(body)
        prompt = str(body.get("input", ""))
        model = str(body.get("model", ""))
        if "fail_both" in prompt:
            return JSONResponse(status_code=500, content={"error": {"message": "boom"}})
        if "fail_pro" in prompt and model == "deepseek-v4-pro":
            return JSONResponse(status_code=500, content={"error": {"message": "boom"}})
        # 记忆分支只看“用户消息”行：现有记忆列表可能含相同字样，不能参与匹配
        user_line = ""
        for line in prompt.splitlines():
            if line.startswith("用户消息："):
                user_line = line.removeprefix("用户消息：")
                break
        if "忘掉" in user_line:
            return _responses_json({"action": "forget", "forget_topics": ["甜品"], "items": [], "updates": []})
        if "低置信" in user_line:
            return _responses_json(
                {
                    "action": "remember",
                    "forget_topics": [],
                    "items": [{"kind": "preference", "scope": "global", "content": "低置信内容", "confidence": 0.3}],
                    "updates": [],
                }
            )
        if "更新" in user_line:
            match = re.search(r"\[记忆(\d+)]", prompt)
            memory_id = int(match.group(1)) if match else 1
            return _responses_json(
                {
                    "action": "remember",
                    "forget_topics": [],
                    "items": [],
                    "updates": [{"memory_id": memory_id, "content": "改为喜欢咸的"}],
                }
            )
        if "记住" in user_line:
            return _responses_json(
                {
                    "action": "remember",
                    "forget_topics": [],
                    "items": [{"kind": "preference", "scope": "global", "content": "喜欢甜品", "confidence": 0.9}],
                    "updates": [],
                }
            )
        if any(marker in prompt for marker in ("no_strict", "bad_once", "always_bad")):
            return JSONResponse(status_code=400, content={"error": {"message": "unsupported"}})
        return _responses_json(VALID_JSON)

    @app.post("/chat/completions")
    async def chat_completions(request: Request) -> Response:
        body = await request.json()
        captured.append(body)
        last = body.get("messages", [{}])[-1]
        content = str(last.get("content", ""))
        model = str(body.get("model", ""))

        if "fail_both" in content:
            return JSONResponse(status_code=500, content={"error": {"message": "boom"}})
        if "fail_always" in content and model == "deepseek-flash":
            return JSONResponse(status_code=500, content={"error": {"message": "boom"}})
        if "fail_twice" in content:
            count = fail_counts.setdefault("fail_twice", 0)
            fail_counts["fail_twice"] = count + 1
            if count < 2:
                return JSONResponse(status_code=500, content={"error": {"message": "boom"}})

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

        if content == "tool" or "工具" in content:

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
    # 其余分节传空字典：pydantic-settings 与配置文件深度合并取值；显式键只为静态检查器放行
    settings = Settings(
        server={},
        database={},
        loop={},
        tools={},
        llm={"base_url": f"http://127.0.0.1:{port}", "api_key": "fake"},
    )
    try:
        yield settings, captured
    finally:
        server.should_exit = True
        thread.join(timeout=5)


@pytest.fixture
async def engine_fixture() -> None:
    """测试期引擎：初始化并随用例结束释放。"""
    init_engine(Settings(**{}).database.url)
    yield
    await dispose_engine()


async def db_up() -> bool:
    """数据库可达性探测：不可达时用例跳过并提示起库。"""
    try:
        async with session_scope() as s:
            await s.execute(text("select 1"))
        return True
    except Exception:
        return False


async def wipe_memories() -> None:
    """清空记忆表：保证记忆用例可重复执行。"""
    async with session_scope() as s:
        repo = MemoryRepo(s)
        for row in await repo.list_all():
            await repo.delete(row.id)
        await s.commit()


async def make_session(title: str) -> int:
    """造一个会话并返回主键。"""
    async with session_scope() as s:
        row = AiSession(work_dir="D:\\tmp", title=title)
        s.add(row)
        await s.commit()
        return row.id


@pytest.fixture
async def client(fake_server: tuple[Settings, list[dict[str, object]]]) -> AsyncIterator[AsyncClient]:
    """应用内嵌客户端：ASGI 传输跑在测试回路，与引擎同回路；注入假嵌入器避免下载模型。"""
    settings, _ = fake_server
    app = create_app(settings, embedder=FakeEmbedder())
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as c:
        yield c
