"""厂商适配：OpenAI 兼容的 chat / embedding / OCR / rerank。"""

# pyright: reportUnknownMemberType=false, reportUnknownVariableType=false, reportUnknownArgumentType=false

import asyncio
import base64
import io
import json
from collections.abc import AsyncIterator
from dataclasses import dataclass, field
from typing import Any, cast

import httpx
from openai import (
    APIConnectionError,
    APIStatusError,
    APITimeoutError,
    AsyncOpenAI,
    AuthenticationError,
    BadRequestError,
    NotFoundError,
    PermissionDeniedError,
    RateLimitError,
)

from lingxi.db.models import Model, ModelVendor


class ProviderError(Exception):
    """厂商调用失败：区分是否可换模型重试。"""

    def __init__(self, code: str, message: str, retryable: bool) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.retryable = retryable


@dataclass
class ChatOutput:
    """对话结果。"""

    content: str
    reasoning: str | None = None
    tool_calls: list[dict[str, object]] = field(default_factory=list)
    usage: dict[str, int] = field(default_factory=dict)


@dataclass
class EmbedOutput:
    """向量结果。"""

    vectors: list[list[float]]
    usage: dict[str, int] = field(default_factory=dict)


@dataclass
class OcrOutput:
    """OCR 结果。"""

    text: str
    fields: dict[str, object] | None
    pages: int
    usage: dict[str, int] = field(default_factory=dict)


@dataclass
class RerankItem:
    """重排结果项。"""

    index: int
    score: float


@dataclass
class RerankOutput:
    """重排结果。"""

    items: list[RerankItem]
    usage: dict[str, int] = field(default_factory=dict)


_CLIENTS: dict[tuple[int, str], AsyncOpenAI] = {}


def _client(vendor: ModelVendor) -> AsyncOpenAI:
    api_key = vendor.api_key or ""
    ref = (vendor.id, api_key)
    client = _CLIENTS.get(ref)
    if client is not None:
        return client
    if not api_key:
        raise ProviderError("MISSING_API_KEY", f"厂商 {vendor.code} 未配置密钥", False)
    client = AsyncOpenAI(
        api_key=api_key,
        base_url=vendor.base_url,
        timeout=(vendor.timeout_ms or 60000) / 1000,
        max_retries=0,
    )
    _CLIENTS[ref] = client
    return client


def _classify(exc: Exception) -> ProviderError:
    if isinstance(exc, (APITimeoutError, APIConnectionError, RateLimitError)):
        return ProviderError("PROVIDER_UNAVAILABLE", str(exc), True)
    if isinstance(exc, (AuthenticationError, PermissionDeniedError)):
        return ProviderError("PROVIDER_AUTH", str(exc), False)
    if isinstance(exc, (BadRequestError, NotFoundError)):
        return ProviderError("PROVIDER_BAD_REQUEST", str(exc), False)
    if isinstance(exc, APIStatusError):
        return ProviderError("PROVIDER_ERROR", str(exc), exc.status_code >= 500 or exc.status_code == 429)
    return ProviderError("PROVIDER_ERROR", str(exc), True)


def _usage(raw: object) -> dict[str, int]:
    """OpenAI usage → 统一字典。"""
    if raw is None:
        return {}
    prompt = getattr(raw, "prompt_tokens", None)
    completion = getattr(raw, "completion_tokens", None)
    total = getattr(raw, "total_tokens", None)
    usage: dict[str, int] = {}
    if isinstance(prompt, int):
        usage["prompt"] = prompt
    if isinstance(completion, int):
        usage["completion"] = completion
    if isinstance(total, int):
        usage["total"] = total
    return usage


async def chat(model: Model, vendor: ModelVendor, payload: dict[str, object]) -> ChatOutput:
    """非流式对话。"""
    request: dict[str, Any] = {
        "model": model.model_key,
        "messages": payload.get("messages") or [],
        "temperature": payload.get("temperature", 0.7),
    }
    if payload.get("max_output_tokens") is not None:
        request["max_tokens"] = payload["max_output_tokens"]
    if payload.get("tools"):
        request["tools"] = payload["tools"]
    if payload.get("response_format"):
        request["response_format"] = payload["response_format"]
    try:
        response = await _client(vendor).chat.completions.create(**request)
    except Exception as exc:  # noqa: BLE001 厂商异常统一分类
        raise _classify(exc) from exc
    message = response.choices[0].message
    tool_calls = cast(
        "list[dict[str, object]]",
        [
            {
                "id": call.id,
                "type": "function",
                "function": {"name": call.function.name, "arguments": call.function.arguments},
            }
            for call in (message.tool_calls or [])
        ],
    )
    return ChatOutput(
        content=message.content or "",
        reasoning=getattr(message, "reasoning_content", None),
        tool_calls=tool_calls,
        usage=_usage(getattr(response, "usage", None)),
    )


async def chat_stream(
    model: Model, vendor: ModelVendor, payload: dict[str, object]
) -> AsyncIterator[dict[str, object]]:
    """流式对话：逐块产出 content / reasoning / tool_calls 增量。"""
    request: dict[str, Any] = {
        "model": model.model_key,
        "messages": payload.get("messages") or [],
        "temperature": payload.get("temperature", 0.7),
        "stream": True,
        "stream_options": {"include_usage": True},
    }
    if payload.get("max_output_tokens") is not None:
        request["max_tokens"] = payload["max_output_tokens"]
    if payload.get("tools"):
        request["tools"] = payload["tools"]
    if payload.get("response_format"):
        request["response_format"] = payload["response_format"]
    try:
        stream = await _client(vendor).chat.completions.create(**request)
    except Exception as exc:  # noqa: BLE001
        raise _classify(exc) from exc
    async for chunk in stream:
        usage = getattr(chunk, "usage", None)
        if usage is not None:
            yield {
                "usage": {
                    "prompt": getattr(usage, "prompt_tokens", None),
                    "completion": getattr(usage, "completion_tokens", None),
                    "total": getattr(usage, "total_tokens", None),
                }
            }
        choice = chunk.choices[0] if chunk.choices else None
        if choice is None:
            continue
        delta = choice.delta
        yield {
            "content": delta.content or "",
            "reasoning": getattr(delta, "reasoning_content", None),
            "tool_calls": [
                {
                    "index": call.index,
                    "id": call.id,
                    "type": "function",
                    "function": {
                        "name": call.function.name if call.function else "",
                        "arguments": call.function.arguments if call.function else "",
                    },
                }
                for call in (delta.tool_calls or [])
            ],
            "finish_reason": choice.finish_reason,
        }


async def embed(model: Model, vendor: ModelVendor, texts: list[str], dimensions: int) -> EmbedOutput:
    """向量化：单批调用。"""
    request: dict[str, Any] = {"model": model.model_key, "input": texts}
    request["dimensions"] = dimensions
    try:
        response = await _client(vendor).embeddings.create(**request)
    except Exception as exc:  # noqa: BLE001
        raise _classify(exc) from exc
    vectors = [[float(value) for value in item.embedding] for item in response.data]
    return EmbedOutput(vectors=vectors, usage=_usage(getattr(response, "usage", None)))


def _image_content(mime: str, data: bytes) -> dict[str, object]:
    encoded = base64.b64encode(data).decode("ascii")
    return {"type": "image_url", "image_url": {"url": f"data:{mime};base64,{encoded}"}}


def _pdf_pages(data: bytes, limit: int) -> list[bytes]:
    """PDF 渲染为 PNG 页：只取前 limit 页。"""
    import pypdfium2 as pdfium  # pyright: ignore[reportMissingTypeStubs]

    pdf = pdfium.PdfDocument(data)
    pages: list[bytes] = []
    for index in range(min(len(pdf), limit)):
        bitmap = pdf[index].render(scale=2).to_pil()
        buffer = io.BytesIO()
        bitmap.save(buffer, format="PNG")
        pages.append(buffer.getvalue())
    return pages


async def _vision_once(
    model: Model, vendor: ModelVendor, prompt: str, images: list[dict[str, object]], schema: dict[str, object] | None
) -> tuple[str, dict[str, int]]:
    content: list[dict[str, object]] = [{"type": "text", "text": prompt}]
    content.extend(images)
    request: dict[str, Any] = {
        "model": model.model_key,
        "messages": [{"role": "user", "content": content}],
    }
    if schema is not None:
        request["response_format"] = {"type": "json_object"}
        request["messages"][0]["content"][0]["text"] = (
            f"{prompt}\n只输出 JSON，符合以下 schema：{json.dumps(schema, ensure_ascii=False)}"
        )
    try:
        response = await _client(vendor).chat.completions.create(**request)
    except Exception as exc:  # noqa: BLE001
        raise _classify(exc) from exc
    return response.choices[0].message.content or "", _usage(getattr(response, "usage", None))


async def ocr(
    model: Model,
    vendor: ModelVendor,
    *,
    data: bytes,
    mime_type: str,
    prompt: str,
    schema: dict[str, object] | None,
    page_limit: int,
) -> OcrOutput:
    """OCR：图片直接识别，扫描 PDF 渲染为图片页后逐页识别。"""
    if mime_type == "application/pdf":
        pages = await asyncio.to_thread(_pdf_pages, data, page_limit)
        images = [_image_content("image/png", page) for page in pages]
    else:
        pages = [data]
        images = [_image_content(mime_type, data)]
    text, usage = await _vision_once(model, vendor, prompt, images, schema)
    fields: dict[str, object] | None = None
    if schema is not None:
        try:
            parsed = json.loads(text)
        except json.JSONDecodeError as exc:
            raise ProviderError("OCR_BAD_JSON", "模型未返回合法 JSON", False) from exc
        if not isinstance(parsed, dict):
            raise ProviderError("OCR_BAD_JSON", "结构化输出必须是对象", False)
        fields = parsed
    return OcrOutput(text=text, fields=fields, pages=len(pages), usage=usage)


async def rerank(model: Model, vendor: ModelVendor, query: str, documents: list[str], top_n: int) -> RerankOutput:
    """重排：调用厂商的 /rerank 端点（非 OpenAI 标准，按通用 JSON 约定）。"""
    api_key = vendor.api_key or ""
    if not api_key:
        raise ProviderError("MISSING_API_KEY", f"厂商 {vendor.code} 未配置密钥", False)
    url = vendor.base_url.rstrip("/") + "/rerank"
    try:
        async with httpx.AsyncClient(timeout=(vendor.timeout_ms or 60000) / 1000) as client:
            response = await client.post(
                url,
                headers={"Authorization": f"Bearer {api_key}"},
                json={"model": model.model_key, "query": query, "documents": documents, "top_n": top_n},
            )
            response.raise_for_status()
            body = response.json()
    except Exception as exc:  # noqa: BLE001
        raise ProviderError("RERANK_ERROR", str(exc), True) from exc
    results = body.get("results") if isinstance(body, dict) else None
    if not isinstance(results, list):
        raise ProviderError("RERANK_BAD_RESPONSE", "重排响应缺少 results", False)
    items = [
        RerankItem(index=int(item.get("index", 0)), score=float(item.get("relevance_score", 0.0)))
        for item in results
        if isinstance(item, dict)
    ]
    return RerankOutput(items=items)
