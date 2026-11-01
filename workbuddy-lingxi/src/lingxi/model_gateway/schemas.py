"""模型能力层接口契约：厂商、模型与能力调用。"""

from typing import Literal

from pydantic import BaseModel, Field

Capability = Literal["CHAT", "EMBEDDING", "OCR", "RERANK"]

CAPABILITIES: tuple[Capability, ...] = ("CHAT", "EMBEDDING", "OCR", "RERANK")


class VendorCreate(BaseModel):
    """新增厂商。"""

    code: str = Field(min_length=1, max_length=64)
    display_name: str = Field(min_length=1, max_length=128)
    base_url: str = Field(min_length=1)
    api_key: str = Field(min_length=1)
    timeout_ms: int | None = None
    max_retries: int | None = None
    enabled: bool = True


class VendorUpdate(BaseModel):
    """编辑厂商：编码不可改；api_key 为空表示保持原密钥。"""

    display_name: str = Field(min_length=1, max_length=128)
    base_url: str = Field(min_length=1)
    api_key: str | None = None
    timeout_ms: int | None = None
    max_retries: int | None = None
    enabled: bool = True


class VendorStatus(BaseModel):
    """厂商启停。"""

    enabled: bool


class ModelCreate(BaseModel):
    """新增模型。"""

    vendor_id: int
    model_key: str = Field(min_length=1, max_length=255)
    display_name: str = Field(min_length=1, max_length=255)
    capability: Capability
    dimension: int | None = None
    context_window: int | None = None
    batch_limit: int | None = None
    rpm_limit: int | None = None
    tpm_limit: int | None = None
    price_in: float | None = None
    price_out: float | None = None
    currency: str = "CNY"
    features: dict[str, object] = Field(default_factory=dict)
    enabled: bool = True


class ModelUpdate(BaseModel):
    """编辑模型：厂商与能力不可改，避免历史记录失真。"""

    model_key: str = Field(min_length=1, max_length=255)
    display_name: str = Field(min_length=1, max_length=255)
    dimension: int | None = None
    context_window: int | None = None
    batch_limit: int | None = None
    rpm_limit: int | None = None
    tpm_limit: int | None = None
    price_in: float | None = None
    price_out: float | None = None
    currency: str = "CNY"
    features: dict[str, object] = Field(default_factory=dict)
    enabled: bool = True


class ModelStatus(BaseModel):
    """模型启停。"""

    enabled: bool


def _mask_key(value: str | None) -> str | None:
    """密钥脱敏：只保留首尾少量字符，永不返回明文。"""
    if not value:
        return None
    if len(value) <= 8:
        return "****"
    return f"{value[:3]}****{value[-4:]}"


def vendor_view(row: object) -> dict[str, object]:
    """厂商序列化：密钥只返回脱敏值与是否已配置。"""
    from lingxi.db.models import ModelVendor

    assert isinstance(row, ModelVendor)
    return {
        "id": str(row.id),
        "code": row.code,
        "display_name": row.display_name,
        "base_url": row.base_url,
        "api_key_masked": _mask_key(row.api_key),
        "api_key_configured": bool(row.api_key),
        "timeout_ms": row.timeout_ms,
        "max_retries": row.max_retries,
        "enabled": row.enabled,
        "created_at": row.created_at.isoformat(),
        "updated_at": row.updated_at.isoformat(),
    }


def model_view(row: object) -> dict[str, object]:
    """模型序列化。"""
    from lingxi.db.models import Model

    assert isinstance(row, Model)
    return {
        "id": str(row.id),
        "vendor_id": str(row.vendor_id),
        "model_key": row.model_key,
        "display_name": row.display_name,
        "capability": row.capability,
        "dimension": row.dimension,
        "context_window": row.context_window,
        "batch_limit": row.batch_limit,
        "rpm_limit": row.rpm_limit,
        "tpm_limit": row.tpm_limit,
        "price_in": float(row.price_in) if row.price_in is not None else None,
        "price_out": float(row.price_out) if row.price_out is not None else None,
        "currency": row.currency,
        "features": row.features,
        "enabled": row.enabled,
        "status": row.status,
        "last_checked_at": row.last_checked_at.isoformat() if row.last_checked_at else None,
        "created_at": row.created_at.isoformat(),
        "updated_at": row.updated_at.isoformat(),
    }
