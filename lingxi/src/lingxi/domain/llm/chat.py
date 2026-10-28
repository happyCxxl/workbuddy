"""DeepSeek 对话通道适配器：Chat Completions 协议；流式、工具、usage 归一化与思考回传。"""

from collections.abc import AsyncIterator
from typing import Any, cast

from openai import AsyncOpenAI

from lingxi.app.config import Settings
from lingxi.domain.ports import ChatMessage, ChatRequest, StreamChunk, ToolCall, Usage

_PROVIDER_EXTRA = "reasoning_content"


class DeepSeekAdapter:
    """对话通道实现：业务不感知供应商协议差异（PY-AI-001）。"""

    def __init__(self, settings: Settings) -> None:
        self._model = settings.llm.chat_model
        self._client = AsyncOpenAI(api_key=settings.llm.api_key or "unset", base_url=settings.llm.base_url)

    @staticmethod
    def _to_provider(message: ChatMessage) -> dict[str, object]:
        """契约消息 → 协议消息：思考内容作为协议外字段原样回传。"""
        body: dict[str, object] = {"role": message.role, "content": message.content}
        if message.reasoning_content is not None:
            body[_PROVIDER_EXTRA] = message.reasoning_content
        return body

    async def stream(self, request: ChatRequest) -> AsyncIterator[StreamChunk]:
        """流式调用：正文/思考增量 → 工具调用 → usage 终块，两种 usage 形态归一。"""
        messages = [self._to_provider(m) for m in request.messages]
        stream = await self._client.chat.completions.create(
            model=self._model,
            # 协议外字段 reasoning_content 需原样透传，SDK 类型不含该键
            messages=cast(Any, messages),
            stream=True,
            stream_options={"include_usage": True},
        )
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
                for call in delta.tool_calls or []:
                    slot = tool_calls.setdefault(call.index, {"id": call.id or "", "name": "", "arguments": ""})
                    if call.function is not None:
                        slot["name"] += call.function.name or ""
                        slot["arguments"] += call.function.arguments or ""
            if chunk.usage is not None:
                usage = Usage(
                    prompt_tokens=chunk.usage.prompt_tokens,
                    completion_tokens=chunk.usage.completion_tokens,
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
