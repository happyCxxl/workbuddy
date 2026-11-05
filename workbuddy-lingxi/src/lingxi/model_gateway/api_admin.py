"""模型管理接口：厂商、模型、测试与用量查询。"""

import base64
from datetime import UTC, datetime

from fastapi import APIRouter
from sqlalchemy import case, func, select

from lingxi.app.auth import AdminDep, CurrentUserDep
from lingxi.contract import ApiError, ErrorCode, R
from lingxi.db.engine import session_scope
from lingxi.db.models import Model, ModelGatewayCall
from lingxi.model_gateway import catalog, service
from lingxi.model_gateway.schemas import (
    ModelCreate,
    ModelStatus,
    ModelUpdate,
    VendorCreate,
    VendorStatus,
    VendorUpdate,
    model_view,
    vendor_view,
)
from lingxi.model_gateway.service import GatewayError

router = APIRouter(prefix="/model-admin")

# OCR 连通性探针：80×32 白底黑字「测试」PNG。
# 不能用 1×1 空图：qwen-vl-ocr 校验图片宽高必须大于 10px，空图返回 400。
OCR_PROBE_PNG = "iVBORw0KGgoAAAANSUhEUgAAAFAAAAAgCAIAAAAKUWg7AAAEeUlEQVR4nO2Zbyh7bRjHr3PIrEUaLfbCkj/JC1lY1KRkjCZ/Okh52miR8oLEK280XkhYlGKMaG1pL7bS8i8s8mJRj9kykuQ/mf+zvWDnKadOst+Gfs/s17Pn8+q+r+91X1fXOfc593U6CI7j4E+g4Geg4Geg4Geg4Geg4Geg4Geg4Geg4Geg4GegPswdGRlZW1u7urrK5XL39vZ8X3B7e3tbW9vXYzU2NiYmJhLjsbGxsrIyu93+SXoUZTKZFxcXqampMzMz4NuCAwICenp66uvrcRzHMAxxQSAQuFuL47hWq+XxeA8PD25zoyiCILGxsevr6+np6REREeB9Aj1oHR0dNputt7eX+MBISkpqbW0lValUSgxEItHh4eHKysr7tWKxODg4WCgU8ni8hYWF0NBQ1/gIghADBoOxtLQEP0KgZ7mnp8dqtRYUFCgUiqioKJFIREpqtdrz2urq6sfHx+PjYwqFQtb2gdE3yGl/f39TUxP4sGAEQcbHxwFAoVAQFpFI9PcbX4ne0NBAbO/BwUFXVSKRREdHC4VC0pKVlQVeJtCdIJVKzWbz8PAwiv7umxxBkMbGRle7TCZjsVi/lLwH6k5gs9kKhUIoFL6+vn43KJ/PJ19sDofDnRuNRnt+foafBXUnZGdnq9VqlUpVVVX18vLyraDNzc0ymay8vNyzW1hY2P39PfwsqAetsLBQLpdTKBSn00kacRz/dJPn5+eLxWIOh0NMr6+vXY80DMPCw8Nvb2/hZwn0LP/1xnuL3W4PCgr6Vo6QkBCZTAYAZrNZKpVKJJLIyMiYmBidTnd5eQl/VMGTk5N6vb67u5u0WK1WOp3+rRwUCkUsFgPA7OysVCrFMIxoyIxG483Njd1up1Kp8FOgnuXl5eWJiYn3p+ju7m5MTMy/kpuIc3BwAD8I6lleW1tLS0sLDw8nphaL5fT0lHw4v4jFYpmenna1E/fZbDbDH1LwxcXF/v5+Xl4e0VctLi729fUR7dfZ2dnMG58mODk54fP5W1tbrlJCQgKNRtvY2CCmGIb19/eDDwvW6XQAQBQMABqNZnR0tL6+HkEQLpdLbsWJiYkPjfR7+Hz+6+trXV3dL3KjaGZmJtlF7+7uGo1G8GHBGo0mJCQkIyODaC0rKytzc3MHBgZWVlbodDqXy7VYLB6WHx8fA8Dd3d38/DyLxQIA4nhTKpVisTg5OVmj0QgEgs3Nzf39fWJDEW7eBXeDzWajUqlFRUXn5+fV1dXEx8Dz8zOh3t/fczgcBoNhNBqtVqvD4bDZbFwul81mEw4OhyMiIoJGoxkMhu3t7dLS0vj4ePIAj46OLi4uNplMV1dXVCq1pqaG6ECmpqZwLwPuhKenp6GhIa1WazKZUlJSVCrVB4fb29ucnBy9Xl9YWEhevpaWFtJBIpEolUocx4+OjphMZklJSVdX19zc3PX19fs4nZ2dAJCWlgYAOzs7uJdBfv9n2sLCgsFgcDqdcXFxFRUVAQEB31rudDpbW1tHRkbKy8vlcjl4GeT/v4fw3+YfEijpPGPQzg4AAAAASUVORK5CYII="


@router.get("/vendors")
async def list_vendors(_admin: AdminDep) -> dict[str, object]:
    """厂商列表。"""
    rows = await catalog.list_vendors()
    return R.ok({"vendors": [vendor_view(row) for row in rows]}).to_dict()


@router.post("/vendors")
async def create_vendor(payload: VendorCreate, _admin: AdminDep) -> dict[str, object]:
    """新增厂商。"""
    row = await catalog.create_vendor(payload)
    return R.ok(vendor_view(row)).to_dict()


@router.put("/vendors/{vendor_id}")
async def update_vendor(vendor_id: int, payload: VendorUpdate, _admin: AdminDep) -> dict[str, object]:
    """编辑厂商。"""
    row = await catalog.update_vendor(vendor_id, payload)
    return R.ok(vendor_view(row)).to_dict()


@router.patch("/vendors/{vendor_id}/status")
async def set_vendor_status(vendor_id: int, payload: VendorStatus, _admin: AdminDep) -> dict[str, object]:
    """启停厂商。"""
    row = await catalog.set_vendor_enabled(vendor_id, payload.enabled)
    return R.ok(vendor_view(row)).to_dict()


@router.delete("/vendors/{vendor_id}")
async def delete_vendor(vendor_id: int, _admin: AdminDep) -> dict[str, object]:
    """删除厂商：存在模型时拒绝。"""
    await catalog.delete_vendor(vendor_id)
    return R.ok(None).to_dict()


@router.get("/models")
async def list_models(
    _admin: AdminDep,
    vendor_id: int | None = None,
    capability: str | None = None,
    enabled: bool | None = None,
) -> dict[str, object]:
    """模型列表。"""
    rows = await catalog.list_models(vendor_id, capability, enabled)
    return R.ok({"models": [model_view(row) for row in rows]}).to_dict()


@router.post("/models")
async def create_model(payload: ModelCreate, _admin: AdminDep) -> dict[str, object]:
    """新增模型。"""
    row = await catalog.create_model(payload)
    return R.ok(model_view(row)).to_dict()


@router.put("/models/{model_id}")
async def update_model(model_id: int, payload: ModelUpdate, _admin: AdminDep) -> dict[str, object]:
    """编辑模型。"""
    row = await catalog.update_model(model_id, payload)
    return R.ok(model_view(row)).to_dict()


@router.patch("/models/{model_id}/status")
async def set_model_status(model_id: int, payload: ModelStatus, _admin: AdminDep) -> dict[str, object]:
    """启停模型。"""
    row = await catalog.set_model_enabled(model_id, payload.enabled)
    return R.ok(model_view(row)).to_dict()


@router.delete("/models/{model_id}")
async def delete_model(model_id: int, _admin: AdminDep) -> dict[str, object]:
    """软删除模型。"""
    await catalog.delete_model(model_id)
    return R.ok(None).to_dict()


@router.get("/models/available")
async def available_models(user: CurrentUserDep, capability: str = "CHAT") -> dict[str, object]:
    """当前用户可选模型：会话页选择用，不要求管理员。"""
    rows = await catalog.list_available(capability)
    vendors = {vendor.id: vendor for vendor in await catalog.list_vendors()}
    return R.ok(
        {
            "models": [
                {
                    "id": str(row.id),
                    "display_name": row.display_name,
                    "model_key": row.model_key,
                    "capability": row.capability,
                    "vendor_code": vendors[row.vendor_id].code if row.vendor_id in vendors else "",
                    "vendor_name": vendors[row.vendor_id].display_name if row.vendor_id in vendors else "",
                }
                for row in rows
            ]
        }
    ).to_dict()


async def _mark_status(model_id: int, status: str) -> None:
    """更新模型状态与最近探测时间。"""
    async with session_scope() as s:
        row = await s.get(Model, model_id)
        if row is not None:
            row.status = status
            row.last_checked_at = datetime.now(UTC)
            await s.commit()


@router.post("/models/{model_id}/test")
async def test_model(model_id: int, _admin: AdminDep) -> dict[str, object]:
    """连通性测试：按能力发一次最小请求。"""
    found = await catalog.get_with_vendor(model_id)
    if found is None:
        raise ApiError(ErrorCode.NOT_FOUND, f"模型不存在：{model_id}")
    model, _vendor = found
    try:
        if model.capability == "CHAT":
            await service.chat(
                request_id=f"test-{model_id}",
                source="model.test",
                model_id=model_id,
                payload={"messages": [{"role": "user", "content": "ping"}], "max_output_tokens": 8},
            )
        elif model.capability == "EMBEDDING":
            await service.embed(
                request_id=f"test-{model_id}",
                source="model.test",
                model_id=model_id,
                texts=["ping"],
                dimensions=model.dimension or 1024,
                input_type="document",
            )
        elif model.capability == "OCR":
            await service.ocr(
                request_id=f"test-{model_id}",
                source="model.test",
                model_id=model_id,
                data=base64.b64decode(OCR_PROBE_PNG),
                mime_type="image/png",
                prompt="识别图片",
                schema=None,
                page_limit=1,
            )
        else:
            await service.rerank(
                request_id=f"test-{model_id}",
                source="model.test",
                model_id=model_id,
                query="ping",
                documents=["ping", "pong"],
                top_n=2,
            )
    except GatewayError as exc:
        await _mark_status(model_id, "DOWN")
        return R.ok({"ok": False, "message": exc.message, "attempts": exc.attempts}).to_dict()
    await _mark_status(model_id, "HEALTHY")
    return R.ok({"ok": True}).to_dict()


@router.get("/usage")
async def usage(
    _admin: AdminDep,
    source: str | None = None,
    model_id: int | None = None,
    capability: str | None = None,
    start: str | None = None,
    end: str | None = None,
    group_by: str = "source",
) -> dict[str, object]:
    """用量聚合：按来源、模型或能力分组。"""
    column = {
        "source": ModelGatewayCall.source,
        "model_id": ModelGatewayCall.model_id,
        "capability": ModelGatewayCall.capability,
    }.get(group_by)
    if column is None:
        raise ApiError(ErrorCode.INVALID_ARGUMENT, f"不支持的分组：{group_by}")
    stmt = select(
        column.label("key"),
        func.count().label("calls"),
        func.coalesce(func.sum(ModelGatewayCall.total_tokens), 0).label("tokens"),
        func.coalesce(func.avg(ModelGatewayCall.latency_ms), 0).label("avg_latency"),
        func.sum(case((ModelGatewayCall.status == "FAILED", 1), else_=0)).label("failures"),
    ).group_by(column)
    if source:
        stmt = stmt.where(ModelGatewayCall.source == source)
    if model_id is not None:
        stmt = stmt.where(ModelGatewayCall.model_id == model_id)
    if capability:
        stmt = stmt.where(ModelGatewayCall.capability == capability)
    if start:
        stmt = stmt.where(ModelGatewayCall.created_at >= datetime.fromisoformat(start))
    if end:
        stmt = stmt.where(ModelGatewayCall.created_at <= datetime.fromisoformat(end))
    async with session_scope() as s:
        rows = (await s.execute(stmt)).all()
    return R.ok(
        {
            "rows": [
                {
                    "key": str(row.key) if row.key is not None else None,
                    "calls": row.calls,
                    "tokens": int(row.tokens),
                    "avg_latency_ms": int(row.avg_latency),
                    "failures": int(row.failures or 0),
                }
                for row in rows
            ]
        }
    ).to_dict()


@router.get("/calls")
async def calls(
    _admin: AdminDep,
    request_id: str | None = None,
    source: str | None = None,
    model_id: int | None = None,
    limit: int = 50,
    offset: int = 0,
) -> dict[str, object]:
    """调用记录分页。"""
    stmt = select(ModelGatewayCall).order_by(ModelGatewayCall.id.desc()).limit(min(limit, 200)).offset(offset)
    if request_id:
        stmt = stmt.where(ModelGatewayCall.request_id == request_id)
    if source:
        stmt = stmt.where(ModelGatewayCall.source == source)
    if model_id is not None:
        stmt = stmt.where(ModelGatewayCall.model_id == model_id)
    async with session_scope() as s:
        rows = list((await s.scalars(stmt)).all())
    return R.ok(
        {
            "calls": [
                {
                    "id": str(row.id),
                    "request_id": row.request_id,
                    "source": row.source,
                    "capability": row.capability,
                    "model_id": str(row.model_id) if row.model_id else None,
                    "vendor_code": row.vendor_code,
                    "model_key": row.model_key,
                    "tokens": row.total_tokens,
                    "latency_ms": row.latency_ms,
                    "status": row.status,
                    "error_code": row.error_code,
                    "attempts": row.attempts,
                    "fallback_used": row.fallback_used,
                    "created_at": row.created_at.isoformat(),
                }
                for row in rows
            ]
        }
    ).to_dict()
