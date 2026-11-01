"""能力调用接口：对话、向量化、OCR、重排与健康检查。"""

import base64
import hmac
import json
import os
from collections.abc import AsyncIterator
from typing import Literal

from fastapi import APIRouter, Depends, Request
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

from lingxi.contract import ApiError, ErrorCode, R
from lingxi.model_gateway import service
from lingxi.model_gateway.service import CallMeta, GatewayError

router = APIRouter(prefix="/internal/v1")

_INTERNAL_TOKEN_ENV = "MODEL_GATEWAY_TOKEN"


class ChatPayload(BaseModel):
    """对话参数。"""

    messages: list[dict[str, object]]
    tools: list[dict[str, object]] = Field(default_factory=list[dict[str, object]])
    stream: bool = False
    temperature: float | None = None
    max_output_tokens: int | None = None
    response_format: dict[str, object] | None = None


class ChatCall(BaseModel):
    """对话调用。"""

    request_id: str
    source: str
    capability: Literal["CHAT"] = "CHAT"
    model_id: int | None = None
    timeout_ms: int | None = None
    payload: ChatPayload


class EmbedPayload(BaseModel):
    """向量化参数。"""

    input: list[str]
    dimensions: int
    input_type: Literal["document", "query"] = "document"


class EmbedCall(BaseModel):
    """向量化调用。"""

    request_id: str
    source: str
    capability: Literal["EMBEDDING"] = "EMBEDDING"
    model_id: int | None = None
    timeout_ms: int | None = None
    payload: EmbedPayload


class OcrPayload(BaseModel):
    """OCR 参数。"""

    file_base64: str
    mime_type: str
    prompt: str = "识别图片文字"
    schema_: dict[str, object] | None = Field(default=None, alias="schema")
    pdf_page_limit: int = 20


class OcrCall(BaseModel):
    """OCR 调用。"""

    request_id: str
    source: str
    capability: Literal["OCR"] = "OCR"
    model_id: int | None = None
    timeout_ms: int | None = None
    payload: OcrPayload


class RerankPayload(BaseModel):
    """重排参数。"""

    query: str
    documents: list[str]
    top_n: int = 10


class RerankCall(BaseModel):
    """重排调用。"""

    request_id: str
    source: str
    capability: Literal["RERANK"] = "RERANK"
    model_id: int | None = None
    timeout_ms: int | None = None
    payload: RerankPayload


def _meta(meta: CallMeta) -> dict[str, object]:
    return {
        "model_id": str(meta.model_id),
        "vendor": meta.vendor,
        "model_key": meta.model_key,
        "latency_ms": meta.latency_ms,
        "attempts": meta.attempts,
        "fallback_used": meta.fallback_used,
        "usage": meta.usage,
    }


async def require_internal(request: Request) -> None:
    """服务间鉴权：令牌缺失时仅允许本机调用。"""
    expected = os.environ.get(_INTERNAL_TOKEN_ENV, "")
    if not expected:
        host = request.client.host if request.client else ""
        if host in ("127.0.0.1", "::1"):
            return
        raise ApiError(ErrorCode.UNAUTHORIZED, "缺少内部令牌")
    token = request.headers.get("X-Internal-Token", "")
    if not hmac.compare_digest(token, expected):
        raise ApiError(ErrorCode.UNAUTHORIZED, "内部令牌无效")


@router.post("/chat")
async def chat(call: ChatCall, _auth: None = Depends(require_internal)) -> object:
    """对话：非流式返回结果，流式返回 SSE。"""
    payload = call.payload.model_dump()
    if not call.payload.stream:
        try:
            result, meta = await service.chat(
                request_id=call.request_id, source=call.source, model_id=call.model_id, payload=payload
            )
        except GatewayError as exc:
            raise ApiError(ErrorCode.MODEL_UNAVAILABLE, exc.message) from exc
        return R.ok(
            {
                "content": result.content,
                "reasoning_content": result.reasoning,
                "tool_calls": result.tool_calls,
                "meta": _meta(meta),
            }
        ).to_dict()

    async def gen() -> AsyncIterator[str]:
        try:
            async for item in service.stream_chat(
                request_id=call.request_id, source=call.source, model_id=call.model_id, payload=payload
            ):
                if item["type"] == "chunk":
                    yield f"event: chunk\ndata: {json.dumps(item['data'], ensure_ascii=False)}\n\n"
                elif item["type"] == "done":
                    yield f"event: done\ndata: {json.dumps(item['meta'], ensure_ascii=False)}\n\n"
                else:
                    yield f"event: error\ndata: {json.dumps({'code': item['code'], 'message': item['message']}, ensure_ascii=False)}\n\n"
        except GatewayError as exc:
            body = {"code": exc.code, "message": exc.message, "attempts": exc.attempts}
            yield f"event: error\ndata: {json.dumps(body, ensure_ascii=False)}\n\n"

    return StreamingResponse(gen(), media_type="text/event-stream")


@router.post("/embed")
async def embed(call: EmbedCall, _auth: None = Depends(require_internal)) -> dict[str, object]:
    """向量化：返回与输入同序的向量。"""
    try:
        result, meta = await service.embed(
            request_id=call.request_id,
            source=call.source,
            model_id=call.model_id,
            texts=call.payload.input,
            dimensions=call.payload.dimensions,
            input_type=call.payload.input_type,
        )
    except GatewayError as exc:
        raise ApiError(ErrorCode.MODEL_UNAVAILABLE, exc.message) from exc
    return R.ok({"vectors": result.vectors, "meta": _meta(meta)}).to_dict()


@router.post("/ocr")
async def ocr(call: OcrCall, _auth: None = Depends(require_internal)) -> dict[str, object]:
    """OCR：图片或扫描 PDF 转文本/结构化字段。"""
    try:
        data = base64.b64decode(call.payload.file_base64, validate=True)
    except ValueError as exc:
        raise ApiError(ErrorCode.INVALID_ARGUMENT, "file_base64 不是合法 Base64") from exc
    try:
        result, meta = await service.ocr(
            request_id=call.request_id,
            source=call.source,
            model_id=call.model_id,
            data=data,
            mime_type=call.payload.mime_type,
            prompt=call.payload.prompt,
            schema=call.payload.schema_,
            page_limit=call.payload.pdf_page_limit,
        )
    except GatewayError as exc:
        raise ApiError(ErrorCode.MODEL_UNAVAILABLE, exc.message) from exc
    return R.ok({"text": result.text, "fields": result.fields, "pages": result.pages, "meta": _meta(meta)}).to_dict()


@router.post("/rerank")
async def rerank(call: RerankCall, _auth: None = Depends(require_internal)) -> dict[str, object]:
    """重排：返回按分数降序的文档下标。"""
    try:
        result, meta = await service.rerank(
            request_id=call.request_id,
            source=call.source,
            model_id=call.model_id,
            query=call.payload.query,
            documents=call.payload.documents,
            top_n=call.payload.top_n,
        )
    except GatewayError as exc:
        raise ApiError(ErrorCode.MODEL_UNAVAILABLE, exc.message) from exc
    return R.ok(
        {
            "results": [{"index": item.index, "score": item.score} for item in result.items],
            "meta": _meta(meta),
        }
    ).to_dict()


@router.get("/health")
async def health(_auth: None = Depends(require_internal)) -> dict[str, object]:
    """健康检查：各能力可用模型数。"""
    from lingxi.model_gateway import catalog

    counts: dict[str, int] = {}
    for capability in ("CHAT", "EMBEDDING", "OCR", "RERANK"):
        counts[capability] = len(await catalog.list_available(capability))
    return R.ok({"models": counts}).to_dict()
