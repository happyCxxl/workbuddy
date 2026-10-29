"""模型出口门面：业务唯一入口——候选路由、熔断、重试、降级与调用明细。"""

import logging
import time
from collections.abc import AsyncIterator

from openai import APIStatusError

from lingxi.app.config import Settings
from lingxi.contract import REQUEST_ID, SESSION_ID, ApiError, ErrorCode
from lingxi.db.engine import session_scope
from lingxi.db.models import ModelCall
from lingxi.db.repo.model_call_repo import ModelCallRepo
from lingxi.domain.llm.chat import DeepSeekAdapter
from lingxi.domain.llm.circuit import CircuitBreaker
from lingxi.domain.llm.structured import StructuredOutput
from lingxi.domain.ports import ChatRequest, ModelT, StreamChunk, Usage

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
        """对话流式：flash 优先；重试耗尽或熔断 → pro 兜底；全败报 60201。"""
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
        """结构化输出：pro 优先；重试耗尽或熔断 → flash 兜底；全败报 60201。"""
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
