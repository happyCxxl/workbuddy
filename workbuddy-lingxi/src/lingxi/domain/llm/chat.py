"""DeepSeek 对话通道适配器：Chat Completions 协议；流式、工具、usage 归一化与思考回传。"""

from collections.abc import AsyncIterator
from typing import Any, cast

from openai import AsyncOpenAI

from lingxi.app.config import Settings
from lingxi.domain.ports import ChatMessage, ChatRequest, StreamChunk, ToolCall, Usage

_PROVIDER_EXTRA = "reasoning_content"
_CACHE_HIT = "prompt_cache_hit_tokens"
_CACHE_MISS = "prompt_cache_miss_tokens"


def _int_extra(obj: object, name: str) -> int:
    """读取协议扩展字段：DeepSeek 的缓存命中数不在 SDK 类型里，可能落在 model_extra。"""
    value = getattr(obj, name, None)
    if value is None:
        extra = getattr(obj, "model_extra", None)
        if isinstance(extra, dict):
            value = cast(dict[str, object], extra).get(name)
    return int(value) if isinstance(value, int) else 0


class DeepSeekAdapter:
    """对话通道实现：业务不感知供应商协议差异（PY-AI-001）。"""

    def __init__(self, settings: Settings, model: str | None = None) -> None:
        self._model = model or settings.llm.chat_model
        self._client = AsyncOpenAI(api_key=settings.llm.api_key or "unset", base_url=settings.llm.base_url)

    @staticmethod
    def _to_provider(message: ChatMessage) -> dict[str, object]:
        """契约消息 → 协议消息：思考内容原样回传；tool 消息带调用回执；assistant 工具调用回显。"""
        body: dict[str, object] = {"role": message.role, "content": message.content}
        if message.reasoning_content is not None:
            body[_PROVIDER_EXTRA] = message.reasoning_content
        if message.role == "tool" and message.tool_call_id is not None:
            body["tool_call_id"] = message.tool_call_id
        if message.tool_calls:
            body["tool_calls"] = [
                {
                    "id": call.id,
                    "type": "function",
                    "function": {"name": call.name, "arguments": call.arguments},
                }
                for call in message.tool_calls
            ]
        return body

    async def stream(self, request: ChatRequest) -> AsyncIterator[StreamChunk]:
        """流式调用：正文/思考增量 → 工具调用 → usage 终块，两种 usage 形态归一。"""
        messages = [self._to_provider(m) for m in request.messages]
        kwargs: dict[str, object] = {
            "model": self._model,
            # 协议外字段 reasoning_content 需原样透传，SDK 类型不含该键
            "messages": messages,
            "stream": True,
            "stream_options": {"include_usage": True},
        }
        if request.tools:
            kwargs["tools"] = [
                {
                    "type": "function",
                    "function": {"name": t.name, "description": t.description, "parameters": t.parameters},
                }
                for t in request.tools
            ]
        # 协议外字段（reasoning_content/tools）需原样透传，SDK 类型不含；返回流类型同样按契约处理
        stream = cast(Any, await self._client.chat.completions.create(**cast(Any, kwargs)))
        tool_calls: dict[int, dict[str, str]] = {}
        usage: Usage | None = None
        async for chunk in stream:
            choice = chunk.choices[0] if chunk.choices else None
            delta = choice.delta if choice else None
            if delta is not None:
                if delta.content:
                    yield StreamChunk(kind="text", text=delta.content, tool_calls=[])
                reasoning = getattr(delta, _PROVIDER_EXTRA, None)
                if reasoning:
                    yield StreamChunk(kind="thinking", text=str(reasoning), tool_calls=[])
                for call in cast(list[Any], delta.tool_calls or []):
                    slot = tool_calls.setdefault(call.index, {"id": call.id or "", "name": "", "arguments": ""})
                    if call.function is not None:
                        slot["name"] += call.function.name or ""
                        slot["arguments"] += call.function.arguments or ""
            if chunk.usage is not None:
                usage = Usage(
                    prompt_tokens=chunk.usage.prompt_tokens,
                    completion_tokens=chunk.usage.completion_tokens,
                    prompt_cache_hit_tokens=_int_extra(chunk.usage, _CACHE_HIT),
                    prompt_cache_miss_tokens=_int_extra(chunk.usage, _CACHE_MISS),
                )
        if tool_calls:
            yield StreamChunk(
                kind="tool_calls",
                tool_calls=[
                    ToolCall(id=slot["id"], name=slot["name"], arguments=slot["arguments"])
                    for slot in tool_calls.values()
                ],
            )
        if usage is not None:
            yield StreamChunk(kind="usage", usage=usage, tool_calls=[])
