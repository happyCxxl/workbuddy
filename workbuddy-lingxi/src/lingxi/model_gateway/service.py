"""模型能力层：候选筛选、智能切换、调用与用量记录。"""

import logging
import time
from collections.abc import AsyncIterator, Awaitable, Callable, Mapping
from dataclasses import dataclass, field

from lingxi.db.engine import session_scope
from lingxi.db.models import Model, ModelGatewayCall, ModelVendor
from lingxi.model_gateway import catalog, providers
from lingxi.model_gateway.providers import ChatOutput, EmbedOutput, OcrOutput, ProviderError, RerankOutput
from lingxi.model_gateway.routing import routing_state

logger = logging.getLogger(__name__)


@dataclass
class CallMeta:
    """一次调用的元信息。"""

    model_id: int
    vendor: str
    model_key: str
    latency_ms: int
    attempts: int
    fallback_used: bool
    usage: dict[str, int] = field(default_factory=dict[str, int])


class GatewayError(Exception):
    """能力层失败：带错误码与尝试明细。"""

    def __init__(self, code: str, message: str, attempts: list[dict[str, object]] | None = None) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.attempts = attempts or []


def _feature(model: Model, name: str) -> bool:
    value = model.features.get(name)
    return bool(value)


def _matches(model: Model, capability: str, constraints: Mapping[str, object]) -> bool:
    if capability == "EMBEDDING":
        return model.dimension == constraints.get("dimensions")
    if capability == "CHAT":
        if constraints.get("stream") and not _feature(model, "stream"):
            return False
        if constraints.get("tools") and not _feature(model, "tools"):
            return False
        if constraints.get("response_format") and not _feature(model, "response_format"):
            return False
    return not (
        capability == "OCR" and constraints.get("schema") is not None and not _feature(model, "structured_output")
    )


async def _candidates(
    capability: str, model_id: int | None, constraints: Mapping[str, object]
) -> list[tuple[Model, ModelVendor]]:
    if model_id is not None:
        found = await catalog.get_with_vendor(model_id)
        if found is None:
            raise GatewayError("MODEL_NOT_FOUND", f"模型不存在：{model_id}")
        model, vendor = found
        if model.capability != capability or not model.enabled or not vendor.enabled:
            raise GatewayError("MODEL_UNAVAILABLE", f"模型不可用：{model_id}")
        return [(model, vendor)]
    rows = await catalog.candidates(capability)
    matched = [(model, vendor) for model, vendor in rows if _matches(model, capability, constraints)]
    ordered = routing_state.order([model for model, _ in matched])
    by_id = {model.id: (model, vendor) for model, vendor in matched}
    return [by_id[model.id] for model in ordered]


async def _record_call(
    *,
    request_id: str,
    source: str,
    capability: str,
    meta: CallMeta | None,
    status: str,
    error_code: str | None,
    error_message: str | None,
) -> None:
    """写调用记录：失败只记日志，不影响调用结果。"""
    try:
        with_usage = meta.usage if meta else {}
        row = ModelGatewayCall(
            request_id=request_id,
            source=source,
            capability=capability,
            model_id=meta.model_id if meta else None,
            vendor_code=meta.vendor if meta else "",
            model_key=meta.model_key if meta else "",
            prompt_tokens=with_usage.get("prompt"),
            completion_tokens=with_usage.get("completion"),
            total_tokens=with_usage.get("total"),
            latency_ms=meta.latency_ms if meta else None,
            status=status,
            error_code=error_code,
            error_message=error_message,
            attempts=meta.attempts if meta else 0,
            fallback_used=meta.fallback_used if meta else False,
        )
        async with session_scope() as s:
            s.add(row)
            await s.commit()
    except Exception as exc:  # noqa: BLE001 记录失败不影响调用
        logger.warning("模型调用记录写入失败：%s", exc)


async def _run(
    capability: str,
    request_id: str,
    source: str,
    model_id: int | None,
    constraints: Mapping[str, object],
    call: Callable[[Model, ModelVendor], Awaitable[object]],
) -> tuple[object, CallMeta]:
    candidates = await _candidates(capability, model_id, constraints)
    if not candidates:
        raise GatewayError("MODEL_UNAVAILABLE", f"没有可用的 {capability} 模型")
    failures: list[dict[str, object]] = []
    attempts = 0
    for model, vendor in candidates:
        if not routing_state.acquire(model):
            continue
        attempts += 1
        started = time.perf_counter()
        try:
            result = await call(model, vendor)  # type: ignore[operator]
            latency = int((time.perf_counter() - started) * 1000)
            usage = getattr(result, "usage", {})
            routing_state.record_success(model.id, latency, usage)
            meta = CallMeta(
                model_id=model.id,
                vendor=vendor.code,
                model_key=model.model_key,
                latency_ms=latency,
                attempts=attempts,
                fallback_used=attempts > 1,
                usage=usage,
            )
            await _record_call(
                request_id=request_id,
                source=source,
                capability=capability,
                meta=meta,
                status="SUCCESS",
                error_code=None,
                error_message=None,
            )
            return result, meta
        except ProviderError as exc:
            routing_state.record_failure(model)
            failures.append(
                {
                    "model_id": model.id,
                    "vendor": vendor.code,
                    "model_key": model.model_key,
                    "code": exc.code,
                    "message": exc.message,
                    "retryable": exc.retryable,
                }
            )
            if not exc.retryable:
                break
    await _record_call(
        request_id=request_id,
        source=source,
        capability=capability,
        meta=None,
        status="FAILED",
        error_code="MODEL_UNAVAILABLE",
        error_message="；".join(str(item["message"]) for item in failures),
    )
    raise GatewayError("MODEL_UNAVAILABLE", "全部候选模型调用失败", failures)


async def chat(
    *,
    request_id: str,
    source: str,
    model_id: int | None,
    payload: dict[str, object],
) -> tuple[ChatOutput, CallMeta]:
    """对话：指定模型或按能力自动切换。"""
    constraints = {
        "tools": bool(payload.get("tools")),
        "response_format": bool(payload.get("response_format")),
    }

    async def call(model: Model, vendor: ModelVendor) -> ChatOutput:
        return await providers.chat(model, vendor, payload)

    result, meta = await _run("CHAT", request_id, source, model_id, constraints, call)
    return result, meta  # type: ignore[return-value]


async def embed(
    *,
    request_id: str,
    source: str,
    model_id: int | None,
    texts: list[str],
    dimensions: int,
    input_type: str,
) -> tuple[EmbedOutput, CallMeta]:
    """向量化：按模型批量上限拆批，任一子批失败换下一个模型。"""
    constraints = {"dimensions": dimensions}
    candidates = await _candidates("EMBEDDING", model_id, constraints)
    if not candidates:
        raise GatewayError("MODEL_UNAVAILABLE", f"没有维度为 {dimensions} 的可用向量模型")
    failures: list[dict[str, object]] = []
    attempts = 0
    for model, vendor in candidates:
        if not routing_state.acquire(model):
            continue
        attempts += 1
        started = time.perf_counter()
        try:
            batch_size = model.batch_limit or 32
            vectors: list[list[float]] = []
            usage: dict[str, int] = {}
            prefix = ""
            if input_type == "query":
                raw_prefix = model.features.get("query_prefix")
                prefix = raw_prefix if isinstance(raw_prefix, str) else ""
            for start in range(0, len(texts), batch_size):
                part = [prefix + text for text in texts[start : start + batch_size]]
                output = await providers.embed(model, vendor, part, dimensions)
                vectors.extend(output.vectors)
                for key, value in output.usage.items():
                    usage[key] = usage.get(key, 0) + value
            latency = int((time.perf_counter() - started) * 1000)
            routing_state.record_success(model.id, latency, usage)
            meta = CallMeta(
                model_id=model.id,
                vendor=vendor.code,
                model_key=model.model_key,
                latency_ms=latency,
                attempts=attempts,
                fallback_used=attempts > 1,
                usage=usage,
            )
            await _record_call(
                request_id=request_id,
                source=source,
                capability="EMBEDDING",
                meta=meta,
                status="SUCCESS",
                error_code=None,
                error_message=None,
            )
            return EmbedOutput(vectors=vectors, usage=usage), meta
        except ProviderError as exc:
            routing_state.record_failure(model)
            failures.append({"model_id": model.id, "vendor": vendor.code, "message": exc.message})
            if not exc.retryable:
                break
    await _record_call(
        request_id=request_id,
        source=source,
        capability="EMBEDDING",
        meta=None,
        status="FAILED",
        error_code="MODEL_UNAVAILABLE",
        error_message="；".join(str(item["message"]) for item in failures),
    )
    raise GatewayError("MODEL_UNAVAILABLE", "全部向量模型调用失败", failures)


async def ocr(
    *,
    request_id: str,
    source: str,
    model_id: int | None,
    data: bytes,
    mime_type: str,
    prompt: str,
    schema: dict[str, object] | None,
    page_limit: int,
) -> tuple[OcrOutput, CallMeta]:
    """OCR：指定模型或按能力自动切换。"""
    constraints = {"schema": schema}

    async def call(model: Model, vendor: ModelVendor) -> OcrOutput:
        return await providers.ocr(
            model, vendor, data=data, mime_type=mime_type, prompt=prompt, schema=schema, page_limit=page_limit
        )

    result, meta = await _run("OCR", request_id, source, model_id, constraints, call)
    return result, meta  # type: ignore[return-value]


async def rerank(
    *,
    request_id: str,
    source: str,
    model_id: int | None,
    query: str,
    documents: list[str],
    top_n: int,
) -> tuple[RerankOutput, CallMeta]:
    """重排：指定模型或按能力自动切换。"""

    async def call(model: Model, vendor: ModelVendor) -> RerankOutput:
        return await providers.rerank(model, vendor, query, documents, top_n)

    result, meta = await _run("RERANK", request_id, source, model_id, {}, call)
    return result, meta  # type: ignore[return-value]


async def stream_chat(
    *,
    request_id: str,
    source: str,
    model_id: int | None,
    payload: dict[str, object],
) -> AsyncIterator[dict[str, object]]:
    """流式对话：首块前可切换，已输出后失败即终止。"""
    constraints = {
        "stream": True,
        "tools": bool(payload.get("tools")),
        "response_format": bool(payload.get("response_format")),
    }
    candidates = await _candidates("CHAT", model_id, constraints)
    if not candidates:
        raise GatewayError("MODEL_UNAVAILABLE", "没有可用的 CHAT 流式模型")
    failures: list[dict[str, object]] = []
    attempts = 0
    for model, vendor in candidates:
        if not routing_state.acquire(model):
            continue
        attempts += 1
        started = time.perf_counter()
        chunks = providers.chat_stream(model, vendor, payload)
        try:
            first = await anext(chunks)
        except StopAsyncIteration:
            first = None
        except ProviderError as exc:
            routing_state.record_failure(model)
            failures.append({"model_id": model.id, "vendor": vendor.code, "message": exc.message})
            if not exc.retryable:
                break
            continue
        meta = {
            "model_id": model.id,
            "vendor": vendor.code,
            "model_key": model.model_key,
            "attempts": attempts,
            "fallback_used": attempts > 1,
        }
        if first is not None:
            yield {"type": "chunk", "data": first, "meta": meta}
        try:
            async for chunk in chunks:
                yield {"type": "chunk", "data": chunk, "meta": meta}
        except ProviderError as exc:
            latency = int((time.perf_counter() - started) * 1000)
            routing_state.record_failure(model)
            await _record_call(
                request_id=request_id,
                source=source,
                capability="CHAT",
                meta=CallMeta(
                    model_id=model.id,
                    vendor=vendor.code,
                    model_key=model.model_key,
                    latency_ms=latency,
                    attempts=attempts,
                    fallback_used=attempts > 1,
                ),
                status="FAILED",
                error_code=exc.code,
                error_message=exc.message,
            )
            yield {"type": "error", "code": exc.code, "message": exc.message}
            return
        latency = int((time.perf_counter() - started) * 1000)
        routing_state.record_success(model.id, latency, {})
        meta_out = CallMeta(
            model_id=model.id,
            vendor=vendor.code,
            model_key=model.model_key,
            latency_ms=latency,
            attempts=attempts,
            fallback_used=attempts > 1,
        )
        await _record_call(
            request_id=request_id,
            source=source,
            capability="CHAT",
            meta=meta_out,
            status="SUCCESS",
            error_code=None,
            error_message=None,
        )
        yield {"type": "done", "meta": meta}
        return
    raise GatewayError("MODEL_UNAVAILABLE", "全部候选模型调用失败", failures)
