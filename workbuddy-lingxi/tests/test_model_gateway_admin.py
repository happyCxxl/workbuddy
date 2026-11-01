"""模型能力层：厂商/模型 CRUD、可用模型与会话模型切换。"""

from uuid import uuid4

import pytest
from httpx2 import AsyncClient
from sqlalchemy import delete, or_

from lingxi.contract import ErrorCode
from lingxi.db.engine import session_scope
from lingxi.db.models import AiSession, Model, ModelVendor


@pytest.fixture(autouse=True)
async def cleanup(engine_fixture: None) -> None:
    """清理本文件创建的模型与厂商，避免污染其他用例的候选集。"""
    yield
    async with session_scope() as s:
        await s.execute(delete(AiSession).where(AiSession.title == "模型切换"))
        await s.execute(delete(Model).where(Model.model_key.in_(["same-embedding", "chat-a", "chat-b"])))
        await s.execute(
            delete(ModelVendor).where(
                or_(
                    ModelVendor.code.like("v1-%"),
                    ModelVendor.code.like("v2-%"),
                    ModelVendor.code.like("chat-%"),
                )
            )
        )
        await s.commit()


def _vendor(code: str) -> dict[str, object]:
    return {
        "code": code,
        "display_name": code,
        "base_url": "https://example.invalid/v1",
        "api_key_env": "EXAMPLE_API_KEY",
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
