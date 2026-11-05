"""模型能力层：厂商/模型 CRUD、可用模型与会话模型切换。"""

import base64
import struct
import zlib
from uuid import uuid4

import pytest
from httpx2 import AsyncClient
from sqlalchemy import delete, or_

from lingxi.contract import ErrorCode
from lingxi.db.engine import session_scope
from lingxi.db.models import AiSession, Model, ModelVendor
from lingxi.model_gateway import api_admin
from lingxi.model_gateway import service as gateway_service
from lingxi.model_gateway.service import GatewayError


@pytest.fixture(autouse=True)
async def cleanup(engine_fixture: None) -> None:
    """清理本文件创建的模型与厂商，避免污染其他用例的候选集。"""
    yield
    async with session_scope() as s:
        await s.execute(delete(AiSession).where(AiSession.title == "模型切换"))
        await s.execute(delete(Model).where(Model.model_key.in_(["same-embedding", "chat-a", "chat-b", "ocr-a"])))
        await s.execute(
            delete(ModelVendor).where(
                or_(
                    ModelVendor.code.like("v1-%"),
                    ModelVendor.code.like("v2-%"),
                    ModelVendor.code.like("chat-%"),
                    ModelVendor.code.like("ocr-%"),
                )
            )
        )
        await s.commit()


def _vendor(code: str) -> dict[str, object]:
    return {
        "code": code,
        "display_name": code,
        "base_url": "https://example.invalid/v1",
        "api_key": "sk-test-key",
        "enabled": True,
    }


def _model(vendor_id: str, key: str, capability: str) -> dict[str, object]:
    body: dict[str, object] = {
        "vendor_id": int(vendor_id),
        "model_key": key,
        "display_name": key,
        "capability": capability,
        "enabled": True,
        "features": {"stream": True, "tools": True},
    }
    if capability == "EMBEDDING":
        body["dimension"] = 1024
    return body


@pytest.mark.usefixtures("engine_fixture")
async def test_vendor_and_model_crud(client: AsyncClient) -> None:
    """同一模型名可录入不同厂商；厂商有模型时禁止删除。"""
    suffix = uuid4().hex[:8]
    first = await client.post("/model-admin/vendors", json=_vendor(f"v1-{suffix}"))
    second = await client.post("/model-admin/vendors", json=_vendor(f"v2-{suffix}"))
    assert first.json()["code"] == 0 and second.json()["code"] == 0
    vendor_one = first.json()["data"]["id"]
    vendor_two = second.json()["data"]["id"]
    listed = await client.get("/model-admin/vendors")
    masked = next(row for row in listed.json()["data"]["vendors"] if row["id"] == vendor_one)
    assert masked["api_key_configured"] is True
    assert masked["api_key_masked"] != "sk-test-key"
    assert "sk-test-key" not in str(masked)

    created = await client.post("/model-admin/models", json=_model(vendor_one, "same-embedding", "EMBEDDING"))
    assert created.json()["code"] == 0
    other = await client.post("/model-admin/models", json=_model(vendor_two, "same-embedding", "EMBEDDING"))
    assert other.json()["code"] == 0

    duplicate = await client.post("/model-admin/models", json=_model(vendor_one, "same-embedding", "EMBEDDING"))
    assert duplicate.json()["code"] == ErrorCode.INVALID_ARGUMENT.code

    removed = await client.delete(f"/model-admin/vendors/{vendor_one}")
    assert removed.json()["code"] == ErrorCode.INVALID_ARGUMENT.code


@pytest.mark.usefixtures("engine_fixture")
async def test_usage_and_calls_query(client: AsyncClient) -> None:
    """用量聚合与调用记录查询可用（空结果也返回 0）。"""
    usage = await client.get("/model-admin/usage", params={"group_by": "source"})
    assert usage.json()["code"] == 0
    assert isinstance(usage.json()["data"]["rows"], list)

    calls = await client.get("/model-admin/calls", params={"limit": 10})
    assert calls.json()["code"] == 0
    assert isinstance(calls.json()["data"]["calls"], list)


@pytest.mark.usefixtures("engine_fixture")
async def test_session_model_switch(client: AsyncClient, tmp_path) -> None:
    """会话创建时选模型，切换后读回新模型。"""
    suffix = uuid4().hex[:8]
    vendor = await client.post("/model-admin/vendors", json=_vendor(f"chat-{suffix}"))
    vendor_id = vendor.json()["data"]["id"]
    one = await client.post("/model-admin/models", json=_model(vendor_id, "chat-a", "CHAT"))
    two = await client.post("/model-admin/models", json=_model(vendor_id, "chat-b", "CHAT"))
    model_one = one.json()["data"]["id"]
    model_two = two.json()["data"]["id"]

    available = await client.get("/model-admin/models/available?capability=CHAT")
    available_ids = {row["id"] for row in available.json()["data"]["models"]}
    assert {model_one, model_two} <= available_ids

    created = await client.post(
        "/sessions", json={"work_dir": str(tmp_path), "title": "模型切换", "model_id": model_one}
    )
    session_id = created.json()["data"]["id"]
    detail = await client.get(f"/sessions/{session_id}")
    assert detail.json()["data"]["model_id"] == model_one

    switched = await client.put(f"/sessions/{session_id}/model", json={"model_id": model_two})
    assert switched.json()["code"] == 0
    detail = await client.get(f"/sessions/{session_id}")
    assert detail.json()["data"]["model_id"] == model_two


def _png_chunks(raw: bytes) -> list[tuple[bytes, bytes]]:
    """解析 PNG 全部块：逐块校验 CRC 并解压 IDAT，任何损坏抛断言。"""
    cursor = 8
    chunks: list[tuple[bytes, bytes]] = []
    idat = bytearray()
    while cursor < len(raw):
        (length,) = struct.unpack(">I", raw[cursor : cursor + 4])
        chunk_type = raw[cursor + 4 : cursor + 8]
        data = raw[cursor + 8 : cursor + 8 + length]
        crc = struct.unpack(">I", raw[cursor + 8 + length : cursor + 12 + length])[0]
        assert zlib.crc32(chunk_type + data) & 0xFFFFFFFF == crc
        chunks.append((chunk_type, data))
        if chunk_type == b"IDAT":
            idat += data
        if chunk_type == b"IEND":
            break
        cursor += 12 + length
    zlib.decompress(bytes(idat))
    return chunks


def test_ocr_probe_image_meets_model_min_size() -> None:
    """OCR 探针图须为可完整解码的 PNG 且宽高大于 10px：qwen-vl-ocr 对过小/损坏图片返回 400。"""
    raw = base64.b64decode(api_admin.OCR_PROBE_PNG, validate=True)
    assert raw[:8] == b"\x89PNG\r\n\x1a\n"
    width = int.from_bytes(raw[16:20], "big")
    height = int.from_bytes(raw[20:24], "big")
    assert width > 10
    assert height > 10
    assert _png_chunks(raw)


@pytest.mark.usefixtures("engine_fixture")
async def test_model_test_returns_provider_failure_details(
    client: AsyncClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    """连通测试失败时透出厂商原始错误（attempts），前端可见 400 真因。"""
    suffix = uuid4().hex[:8]
    vendor = await client.post("/model-admin/vendors", json=_vendor(f"ocr-{suffix}"))
    vendor_id = vendor.json()["data"]["id"]
    created = await client.post("/model-admin/models", json=_model(vendor_id, "ocr-a", "OCR"))
    model_id = created.json()["data"]["id"]

    async def fail_ocr(**kwargs: object) -> tuple[object, object]:
        raise GatewayError(
            "MODEL_UNAVAILABLE",
            "全部候选模型调用失败",
            [
                {
                    "model_id": model_id,
                    "vendor": f"ocr-{suffix}",
                    "model_key": "ocr-a",
                    "code": "PROVIDER_BAD_REQUEST",
                    "message": "Error code: 400 - image too small",
                    "retryable": False,
                }
            ],
        )

    monkeypatch.setattr(gateway_service, "ocr", fail_ocr)
    result = await client.post(f"/model-admin/models/{model_id}/test")
    assert result.json()["code"] == 0
    data = result.json()["data"]
    assert data["ok"] is False
    attempts = data["attempts"]
    assert attempts[0]["code"] == "PROVIDER_BAD_REQUEST"
    assert "image too small" in attempts[0]["message"]
