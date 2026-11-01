"""模型出口门面：业务唯一入口——候选路由、熔断、重试、降级与调用明细。"""

# pyright: reportUnknownMemberType=false, reportUnknownVariableType=false, reportUnknownArgumentType=false

import json
import logging
import time
from collections.abc import AsyncIterator
from typing import cast
from uuid import uuid4

from openai import APIStatusError

from lingxi.app.config import Settings
from lingxi.contract import REQUEST_ID, SESSION_ID, ApiError, ErrorCode
from lingxi.db.engine import session_scope
from lingxi.db.models import ModelCall
from lingxi.db.repo.model_call_repo import ModelCallRepo
from lingxi.domain.llm.chat import DeepSeekAdapter
from lingxi.domain.llm.circuit import CircuitBreaker
from lingxi.domain.llm.structured import StructuredOutput
from lingxi.domain.ports import ChatMessage, ChatRequest, ModelT, StreamChunk, ToolCall, Usage
from lingxi.model_gateway import catalog, service
from lingxi.model_gateway.service import GatewayError

logger = logging.getLogger(__name__)


def _retryable(exc: APIStatusError) -> bool:
    """仅服务端错误与限流可重试；4xx 参数类错误不重试。"""
    return exc.status_code == 429 or exc.status_code >= 500


class LlmGateway:
    """统一出口：业务只依赖此入口（PY-AI-001），不感知供应商与可靠性细节。"""

    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._retry_budget = settings.llm.retry_budget
        self._flash = DeepSeekAdapter(settings)
        self._pro = DeepSeekAdapter(settings, model=settings.llm.reasoner_model)
        self._structured = StructuredOutput(settings)
        self._flash_breaker = CircuitBreaker(settings.llm.failure_rate_threshold, settings.llm.cooldown_seconds)
        self._pro_breaker = CircuitBreaker(settings.llm.failure_rate_threshold, settings.llm.cooldown_seconds)

    async def stream(self, request: ChatRequest) -> AsyncIterator[StreamChunk]:
        """对话流式：有可用模型时走能力层；否则回落直连通道。"""
        if await self._use_gateway(request.model_id):
            async for chunk in self._gateway_stream(request):
                yield chunk
            return
        last_error: Exception | None = None
        for model, adapter, breaker in self._chat_chain():
            for _attempt in range(self._retry_budget + 1):
                if not breaker.allow():
                    break
                start = time.monotonic()
                usage: Usage | None = None
                emitted = False
                try:
                    async for chunk in adapter.stream(request):
                        if chunk.kind == "usage":
                            usage = chunk.usage
                        emitted = True
                        yield chunk
                    breaker.record_success()
                    await self._record(model, usage, self._elapsed_ms(start), None)
                    return
                except ApiError:
                    raise
                except APIStatusError as exc:
                    breaker.record_failure()
                    last_error = exc
                    await self._record(model, usage, self._elapsed_ms(start), exc.status_code)
                    if emitted:
                        # 已向客户端输出过正文：重试会重复内容，直接收口
                        raise ApiError(
                            ErrorCode.MODEL_UNAVAILABLE, f"流式中断，已输出部分内容不再重试：{type(exc).__name__}"
                        ) from exc
                    if not _retryable(exc):
                        break
                except Exception as exc:
                    breaker.record_failure()
                    last_error = exc
                    await self._record(model, usage, self._elapsed_ms(start), ErrorCode.MODEL_UNAVAILABLE.code)
                    if emitted:
                        # 同上：半截正文不能靠重试补齐
                        raise ApiError(
                            ErrorCode.MODEL_UNAVAILABLE, f"流式中断，已输出部分内容不再重试：{type(exc).__name__}"
                        ) from exc
        raise ApiError(ErrorCode.MODEL_UNAVAILABLE, f"模型出口全部候选不可用：{type(last_error).__name__}")

    async def structured(self, prompt: str, model_type: type[ModelT]) -> ModelT:
        """结构化输出：有可用模型时走能力层，否则回落直连通道。"""
        if await self._use_gateway(None):
            return await self._gateway_structured(prompt, model_type)
        last_error: Exception | None = None
        for model, breaker in self._high_chain():
            for _attempt in range(self._retry_budget + 1):
                if not breaker.allow():
                    break
                start = time.monotonic()
                try:
                    result = await self._structured.structured(prompt, model_type, model=model)
                    breaker.record_success()
                    await self._record(model, None, self._elapsed_ms(start), None)
                    return result
                except ApiError:
                    raise
                except APIStatusError as exc:
                    breaker.record_failure()
                    last_error = exc
                    await self._record(model, None, self._elapsed_ms(start), exc.status_code)
                    if not _retryable(exc):
                        break
                except Exception as exc:
                    breaker.record_failure()
                    last_error = exc
                    await self._record(model, None, self._elapsed_ms(start), ErrorCode.MODEL_UNAVAILABLE.code)
        raise ApiError(ErrorCode.MODEL_UNAVAILABLE, f"模型出口全部候选不可用：{type(last_error).__name__}")

    async def _use_gateway(self, model_id: int | None) -> bool:
        """指定模型或已录入 CHAT 模型时走能力层。"""
        if model_id is not None:
            return True
        try:
            return bool(await catalog.list_available("CHAT"))
        except Exception:  # noqa: BLE001 数据库不可用时回落直连
            return False

    async def _gateway_stream(self, request: ChatRequest) -> AsyncIterator[StreamChunk]:
        """能力层流式：把能力层增量转成回合事件需要的块。"""
        payload: dict[str, object] = {
            "messages": [self._payload_message(message) for message in request.messages],
            "tools": [
                {
                    "type": "function",
                    "function": {"name": tool.name, "description": tool.description, "parameters": tool.parameters},
                }
                for tool in request.tools
            ],
            "stream": True,
        }
        tool_calls: dict[int, dict[str, str]] = {}
        started = time.monotonic()
        try:
            async for item in service.stream_chat(
                request_id=REQUEST_ID.get() or uuid4().hex,
                source="assistant.chat",
                model_id=request.model_id,
                payload=payload,
            ):
                if item["type"] == "error":
                    raise ApiError(ErrorCode.MODEL_UNAVAILABLE, str(item.get("message") or "模型调用失败"))
                if item["type"] == "done":
                    meta = cast("dict[str, object]", item.get("meta") or {})
                    raw_usage = cast("dict[str, object]", item.get("usage") or {})
                    usage = Usage(
                        prompt_tokens=int(str(raw_usage.get("prompt") or 0)),
                        completion_tokens=int(str(raw_usage.get("completion") or 0)),
                    )
                    label = f"{meta.get('vendor') or 'unknown'}/{meta.get('model_key') or 'unknown'}"
                    await self._record(label, usage, self._elapsed_ms(started), None)
                    continue
                if item["type"] != "chunk":
                    continue
                data = cast("dict[str, object]", item["data"])
                content = data.get("content")
                if isinstance(content, str) and content:
                    yield StreamChunk(kind="text", text=content, tool_calls=[])
                reasoning = data.get("reasoning")
                if isinstance(reasoning, str) and reasoning:
                    yield StreamChunk(kind="thinking", text=reasoning, tool_calls=[])
                raw_calls = data.get("tool_calls")
                for raw_delta in raw_calls if isinstance(raw_calls, list) else []:
                    delta = cast("dict[str, object]", raw_delta)
                    index = int(str(delta.get("index") or 0))
                    slot = tool_calls.setdefault(index, {"id": "", "name": "", "arguments": ""})
                    call_id = delta.get("id")
                    if isinstance(call_id, str):
                        slot["id"] = call_id
                    function = delta.get("function")
                    if isinstance(function, dict):
                        slot["name"] += str(function.get("name") or "")
                        slot["arguments"] += str(function.get("arguments") or "")
        except GatewayError as exc:
            raise ApiError(ErrorCode.MODEL_UNAVAILABLE, exc.message) from exc
        if tool_calls:
            yield StreamChunk(
                kind="tool_calls",
                tool_calls=[
                    ToolCall(id=slot["id"], name=slot["name"], arguments=slot["arguments"])
                    for slot in tool_calls.values()
                ],
            )

    async def _gateway_structured(self, prompt: str, model_type: type[ModelT]) -> ModelT:
        """能力层结构化输出：JSON 模式 + 本地 schema 校验。"""
        schema = json.dumps(model_type.model_json_schema(), ensure_ascii=False)
        try:
            result, _meta = await service.chat(
                request_id=REQUEST_ID.get() or uuid4().hex,
                source="assistant.structured",
                model_id=None,
                payload={
                    "messages": [{"role": "user", "content": f"{prompt}\n只输出 JSON，符合以下 schema：{schema}"}],
                    "response_format": {"type": "json_object"},
                },
            )
        except GatewayError as exc:
            raise ApiError(ErrorCode.MODEL_UNAVAILABLE, exc.message) from exc
        try:
            return model_type.model_validate_json(result.content)
        except Exception as exc:  # noqa: BLE001 模型输出不合法
            raise ApiError(ErrorCode.STRUCTURED_OUTPUT_INVALID, "结构化输出解析失败") from exc

    @staticmethod
    def _payload_message(message: ChatMessage) -> dict[str, object]:
        """契约消息 → 能力层消息。"""
        body: dict[str, object] = {"role": message.role, "content": message.content}
        if message.reasoning_content is not None:
            body["reasoning_content"] = message.reasoning_content
        if message.tool_call_id is not None:
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

    def _chat_chain(self) -> list[tuple[str, DeepSeekAdapter, CircuitBreaker]]:
        """对话任务候选链：常规优先，高能力兜底。"""
        return [
            (self._settings.llm.chat_model, self._flash, self._flash_breaker),
            (self._settings.llm.reasoner_model, self._pro, self._pro_breaker),
        ]

    def _high_chain(self) -> list[tuple[str, CircuitBreaker]]:
        """高能力任务候选链：高能力优先，常规兜底。"""
        return [
            (self._settings.llm.reasoner_model, self._pro_breaker),
            (self._settings.llm.chat_model, self._flash_breaker),
        ]

    @staticmethod
    def _elapsed_ms(start: float) -> int:
        return int((time.monotonic() - start) * 1000)

    @staticmethod
    async def _record(model: str, usage: Usage | None, latency_ms: int, error_code: int | None) -> None:
        """调用明细落库：成功失败都记；写入失败仅记日志，不阻断主流程。"""
        try:
            async with session_scope() as s:
                await ModelCallRepo(s).insert(
                    ModelCall(
                        request_id=REQUEST_ID.get(),
                        session_id=SESSION_ID.get(),
                        model=model,
                        prompt_tokens=usage.prompt_tokens if usage else None,
                        completion_tokens=usage.completion_tokens if usage else None,
                        prompt_cache_hit_tokens=usage.prompt_cache_hit_tokens if usage else None,
                        prompt_cache_miss_tokens=usage.prompt_cache_miss_tokens if usage else None,
                        latency_ms=latency_ms,
                        error_code=error_code,
                    )
                )
                await s.commit()
        except Exception as exc:
            logger.warning("调用明细写入失败：%s", exc)
