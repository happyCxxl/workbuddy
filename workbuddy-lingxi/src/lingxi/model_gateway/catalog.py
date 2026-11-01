"""厂商与模型注册：CRUD 与查询。"""

from datetime import UTC, datetime

from sqlalchemy import select

from lingxi.contract import ApiError, ErrorCode
from lingxi.db.engine import session_scope
from lingxi.db.models import Model, ModelVendor
from lingxi.model_gateway.schemas import ModelCreate, ModelUpdate, VendorCreate, VendorUpdate


async def list_vendors() -> list[ModelVendor]:
    """全部厂商：按创建顺序。"""
    async with session_scope() as s:
        return list((await s.scalars(select(ModelVendor).order_by(ModelVendor.id))).all())


async def create_vendor(payload: VendorCreate) -> ModelVendor:
    """新增厂商：编码唯一。"""
    async with session_scope() as s:
        exists = await s.scalar(select(ModelVendor).where(ModelVendor.code == payload.code))
        if exists is not None:
            raise ApiError(ErrorCode.INVALID_ARGUMENT, f"厂商编码已存在：{payload.code}")
        row = ModelVendor(**payload.model_dump())
        s.add(row)
        await s.commit()
        return row


async def update_vendor(vendor_id: int, payload: VendorUpdate) -> ModelVendor:
    """编辑厂商：编码不可改。"""
    async with session_scope() as s:
        row = await s.get(ModelVendor, vendor_id)
        if row is None:
            raise ApiError(ErrorCode.NOT_FOUND, f"厂商不存在：{vendor_id}")
        for field, value in payload.model_dump().items():
            setattr(row, field, value)
        await s.commit()
        return row


async def set_vendor_enabled(vendor_id: int, enabled: bool) -> ModelVendor:
    """启停厂商。"""
    async with session_scope() as s:
        row = await s.get(ModelVendor, vendor_id)
        if row is None:
            raise ApiError(ErrorCode.NOT_FOUND, f"厂商不存在：{vendor_id}")
        row.enabled = enabled
        await s.commit()
        return row


async def delete_vendor(vendor_id: int) -> None:
    """删除厂商：其下存在模型时拒绝。"""
    async with session_scope() as s:
        row = await s.get(ModelVendor, vendor_id)
        if row is None:
            raise ApiError(ErrorCode.NOT_FOUND, f"厂商不存在：{vendor_id}")
        used = await s.scalar(select(Model.id).where(Model.vendor_id == vendor_id).limit(1))
        if used is not None:
            raise ApiError(ErrorCode.INVALID_ARGUMENT, "厂商下存在模型，不能删除")
        await s.delete(row)
        await s.commit()


async def list_models(
    vendor_id: int | None = None,
    capability: str | None = None,
    enabled: bool | None = None,
) -> list[Model]:
    """模型列表：默认排除软删除。"""
    stmt = select(Model).where(Model.deleted_at.is_(None))
    if vendor_id is not None:
        stmt = stmt.where(Model.vendor_id == vendor_id)
    if capability is not None:
        stmt = stmt.where(Model.capability == capability)
    if enabled is not None:
        stmt = stmt.where(Model.enabled.is_(enabled))
    stmt = stmt.order_by(Model.id.desc())
    async with session_scope() as s:
        return list((await s.scalars(stmt)).all())


async def create_model(payload: ModelCreate) -> Model:
    """新增模型：厂商存在、嵌入维度必填、同厂商同能力模型名唯一。"""
    if payload.capability == "EMBEDDING" and (payload.dimension is None or payload.dimension <= 0):
        raise ApiError(ErrorCode.INVALID_ARGUMENT, "EMBEDDING 模型必须填写维度")
    async with session_scope() as s:
        vendor = await s.get(ModelVendor, payload.vendor_id)
        if vendor is None:
            raise ApiError(ErrorCode.NOT_FOUND, f"厂商不存在：{payload.vendor_id}")
        duplicate = await s.scalar(
            select(Model.id).where(
                Model.vendor_id == payload.vendor_id,
                Model.model_key == payload.model_key,
                Model.capability == payload.capability,
                Model.deleted_at.is_(None),
            )
        )
        if duplicate is not None:
            raise ApiError(ErrorCode.INVALID_ARGUMENT, "同厂商同能力的模型名已存在")
        row = Model(**payload.model_dump())
        s.add(row)
        await s.commit()
        return row


async def update_model(model_id: int, payload: ModelUpdate) -> Model:
    """编辑模型：厂商与能力不可改。"""
    data = payload.model_dump()
    async with session_scope() as s:
        row = await s.get(Model, model_id)
        if row is None or row.deleted_at is not None:
            raise ApiError(ErrorCode.NOT_FOUND, f"模型不存在：{model_id}")
        if row.capability == "EMBEDDING" and (data["dimension"] is None or data["dimension"] <= 0):
            raise ApiError(ErrorCode.INVALID_ARGUMENT, "EMBEDDING 模型必须填写维度")
        duplicate = await s.scalar(
            select(Model.id).where(
                Model.vendor_id == row.vendor_id,
                Model.model_key == data["model_key"],
                Model.capability == row.capability,
                Model.id != model_id,
                Model.deleted_at.is_(None),
            )
        )
        if duplicate is not None:
            raise ApiError(ErrorCode.INVALID_ARGUMENT, "同厂商同能力的模型名已存在")
        for field, value in data.items():
            setattr(row, field, value)
        await s.commit()
        return row


async def set_model_enabled(model_id: int, enabled: bool) -> Model:
    """启停模型。"""
    async with session_scope() as s:
        row = await s.get(Model, model_id)
        if row is None or row.deleted_at is not None:
            raise ApiError(ErrorCode.NOT_FOUND, f"模型不存在：{model_id}")
        row.enabled = enabled
        await s.commit()
        return row


async def delete_model(model_id: int) -> None:
    """软删除模型：从列表与候选集移除，调用记录保留。"""
    async with session_scope() as s:
        row = await s.get(Model, model_id)
        if row is None or row.deleted_at is not None:
            raise ApiError(ErrorCode.NOT_FOUND, f"模型不存在：{model_id}")
        row.deleted_at = datetime.now(UTC)
        row.enabled = False
        await s.commit()


async def list_available(capability: str) -> list[Model]:
    """当前可用的模型：启用、未删除、厂商启用。"""
    stmt = (
        select(Model)
        .join(ModelVendor, ModelVendor.id == Model.vendor_id)
        .where(
            Model.capability == capability,
            Model.enabled.is_(True),
            Model.deleted_at.is_(None),
            ModelVendor.enabled.is_(True),
        )
        .order_by(Model.id)
    )
    async with session_scope() as s:
        return list((await s.scalars(stmt)).all())


async def get_with_vendor(model_id: int) -> tuple[Model, ModelVendor] | None:
    """按主键取模型与厂商；软删除视为不存在。"""
    async with session_scope() as s:
        row = await s.get(Model, model_id)
        if row is None or row.deleted_at is not None:
            return None
        vendor = await s.get(ModelVendor, row.vendor_id)
        if vendor is None:
            return None
        return row, vendor


async def candidates(capability: str) -> list[tuple[Model, ModelVendor]]:
    """能力候选集：启用、未删除、厂商启用。"""
    stmt = (
        select(Model, ModelVendor)
        .join(ModelVendor, ModelVendor.id == Model.vendor_id)
        .where(
            Model.capability == capability,
            Model.enabled.is_(True),
            Model.deleted_at.is_(None),
            ModelVendor.enabled.is_(True),
        )
        .order_by(Model.id)
    )
    async with session_scope() as s:
        rows = (await s.execute(stmt)).all()
        return [(row[0], row[1]) for row in rows]
