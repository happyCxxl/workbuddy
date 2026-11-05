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
import jsonschema
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


async def chat(
    model: Model, vendor: ModelVendor, payload: dict[str, object], mechanism: str | None = None
) -> ChatOutput:
    """非流式对话：带 schema 时按分层机制产出合规 JSON（content 为 JSON 文本），否则走普通对话。"""
    request: dict[str, Any] = {
        "model": model.model_key,
        "messages": payload.get("messages") or [],
        "temperature": payload.get("temperature", 0.7),
    }
    if payload.get("max_output_tokens") is not None:
        request["max_tokens"] = payload["max_output_tokens"]
    if payload.get("tools"):
        request["tools"] = payload["tools"]
    schema = payload.get("schema")
    if schema is not None:
        fields, usage = await _schema_validated_text(
            model, vendor, request, cast("dict[str, object]", schema), mechanism or "manual"
        )
        return ChatOutput(content=json.dumps(fields, ensure_ascii=False), reasoning=None, tool_calls=[], usage=usage)
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
                    "cache_hit": getattr(usage, "prompt_cache_hit_tokens", None),
                    "cache_miss": getattr(usage, "prompt_cache_miss_tokens", None),
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


async def _completion(model: Model, vendor: ModelVendor, request: dict[str, Any]) -> tuple[str, dict[str, int]]:
    """单次补全调用：返回文本与用量。"""
    try:
        response = await _client(vendor).chat.completions.create(**request)
    except Exception as exc:  # noqa: BLE001
        raise _classify(exc) from exc
    return response.choices[0].message.content or "", _usage(getattr(response, "usage", None))


def _extract_json(text: str) -> str | None:
    """手写解析：从自由文本中抠出 JSON（剥代码围栏、截取首 { 到末 }）。"""
    cleaned = text.strip()
    for fence in ("```json", "```"):
        if cleaned.startswith(fence):
            cleaned = cleaned[len(fence) :].lstrip()
            if cleaned.endswith("```"):
                cleaned = cleaned[:-3].rstrip()
    if not cleaned.startswith("{"):
        start = cleaned.find("{")
        if start < 0:
            return None
        cleaned = cleaned[start:]
    end = cleaned.rfind("}")
    if end < 0:
        return None
    return cleaned[: end + 1]


def _extract_fields(
    text: str, schema: dict[str, object], mechanism: str
) -> tuple[dict[str, object] | None, str | None]:
    """输出 → (fields, error)：manual 层先手写抠 JSON；解析与 schema 校验各层通用。"""
    if mechanism == "manual":
        candidate = _extract_json(text)
        if candidate is None:
            return None, "模型未返回可解析的 JSON"
        text = candidate
    try:
        parsed = json.loads(text)
    except json.JSONDecodeError as exc:
        return None, f"模型未返回合法 JSON：{exc.msg}"
    if not isinstance(parsed, dict):
        return None, "结构化输出必须是 JSON 对象"
    try:
        jsonschema.validate(parsed, schema)
    except jsonschema.ValidationError as exc:
        return None, f"schema 校验失败：{exc.message}"
    return cast("dict[str, object]", parsed), None


def _ensure_json_word(messages: list[dict[str, object]]) -> None:
    """json_object 模式要求提示词含 JSON 字样：最后一条消息补上（文本与视觉多模态都处理）。"""
    if not messages:
        return
    last = messages[-1]
    content = last.get("content")
    if isinstance(content, str):
        if "json" not in content.lower():
            messages[-1] = {**last, "content": content + "\n请输出 JSON。"}
    elif isinstance(content, list):
        for item in content:
            if isinstance(item, dict) and item.get("type") == "text":
                text = str(item.get("text", ""))
                if "json" not in text.lower():
                    item["text"] = text + "\n请输出 JSON。"
                break


def _mechanism_request(request: dict[str, Any], mechanism: str, schema: dict[str, object]) -> dict[str, Any]:
    """按机制附加请求参数：strict 解码层强制；json_object 约束合法 JSON；manual 不附加。"""
    updated = dict(request)
    if mechanism == "strict":
        updated["response_format"] = {
            "type": "json_schema",
            "json_schema": {"name": "structured", "schema": schema, "strict": True},
        }
    elif mechanism == "json_object":
        updated["response_format"] = {"type": "json_object"}
        _ensure_json_word(updated["messages"])
    return updated


def _feedback_request(request: dict[str, Any], feedback: str) -> dict[str, Any]:
    """重试请求：反馈拼进最后一条消息。"""
    updated = dict(request)
    messages = [dict(message) for message in updated["messages"]]
    last = dict(messages[-1])
    content = last.get("content")
    if isinstance(content, str):
        last["content"] = content + "\n" + feedback
    elif isinstance(content, list):
        for item in content:
            if isinstance(item, dict) and item.get("type") == "text":
                item["text"] = str(item.get("text", "")) + "\n" + feedback
                break
    messages[-1] = last
    updated["messages"] = messages
    return updated


def _merge_usage(first: dict[str, int], second: dict[str, int]) -> dict[str, int]:
    """两次调用用量合并：token 计数相加，其余字段后者覆盖。"""
    merged = dict(first)
    for key in ("prompt", "completion", "total"):
        merged[key] = merged.get(key, 0) + second.get(key, 0)
    return merged


async def _schema_validated_text(
    model: Model, vendor: ModelVendor, request: dict[str, Any], schema: dict[str, object], mechanism: str
) -> tuple[dict[str, object], dict[str, int]]:
    """按机制生成并校验 schema 输出：不合规回喂错误与 schema 重试一次，仍失败抛 SCHEMA_VIOLATION（可换模型）。"""
    text, usage = await _completion(model, vendor, _mechanism_request(request, mechanism, schema))
    fields, error = _extract_fields(text, schema, mechanism)
    if error is None:
        return cast("dict[str, object]", fields), usage
    feedback = (
        f"上次输出不符合要求：{error}。请严格按以下 JSON 结构重新输出：\n{json.dumps(schema, ensure_ascii=False)}"
    )
    retry_text, retry_usage = await _completion(
        model, vendor, _mechanism_request(_feedback_request(request, feedback), mechanism, schema)
    )
    usage = _merge_usage(usage, retry_usage)
    fields, error = _extract_fields(retry_text, schema, mechanism)
    if error is not None:
        raise ProviderError("SCHEMA_VIOLATION", f"模型输出不符合 schema（已重试）：{error}", True)
    return cast("dict[str, object]", fields), usage


def _vision_request(model: Model, prompt: str, images: list[dict[str, object]]) -> dict[str, Any]:
    """视觉调用请求：文本提示词 + 图片列表。"""
    content: list[dict[str, object]] = [{"type": "text", "text": prompt}]
    content.extend(images)
    return {"model": model.model_key, "messages": [{"role": "user", "content": content}]}


async def ocr(
    model: Model,
    vendor: ModelVendor,
    *,
    data: bytes,
    mime_type: str,
    prompt: str,
    schema: dict[str, object] | None,
    page_limit: int,
    mechanism: str | None = None,
) -> OcrOutput:
    """OCR：图片直接识别，扫描 PDF 渲染为图片页后逐页识别。

    带 schema 时按 service 分层给的机制执行（strict/json_object/manual），形状保障不靠提示词；
    无 schema 走纯文本输出。
    """
    if mime_type == "application/pdf":
        pages = await asyncio.to_thread(_pdf_pages, data, page_limit)
        images = [_image_content("image/png", page) for page in pages]
    else:
        pages = [data]
        images = [_image_content(mime_type, data)]
    request = _vision_request(model, prompt, images)
    fields: dict[str, object] | None = None
    if schema is None:
        text, usage = await _completion(model, vendor, request)
    else:
        fields, usage = await _schema_validated_text(model, vendor, request, schema, mechanism or "manual")
        text = json.dumps(fields, ensure_ascii=False)
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
