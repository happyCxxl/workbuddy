"""模型管理接口：厂商、模型与可用模型查询。"""

from fastapi import APIRouter

from lingxi.app.auth import AdminDep, CurrentUserDep
from lingxi.contract import R
from lingxi.model_gateway import catalog
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

router = APIRouter(prefix="/model-admin")


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
    return R.ok(
        {
            "models": [
                {
                    "id": str(row.id),
                    "display_name": row.display_name,
                    "model_key": row.model_key,
                    "capability": row.capability,
                }
                for row in rows
            ]
        }
    ).to_dict()
