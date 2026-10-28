"""人设接口：启用版本读取、保存新版本、版本列表与启用切换。"""

from fastapi import APIRouter
from pydantic import BaseModel

from lingxi.contract import R
from lingxi.domain.persona.engine import (
    activate_version,
    create_persona,
    current_persona,
    get_version,
    list_versions,
)
from lingxi.domain.persona.models import PersonaFields

router = APIRouter()


class SavePersonaRequest(BaseModel):
    """保存新版本请求：八字段。"""

    fields: PersonaFields


def _serialize_fields(fields: PersonaFields) -> dict[str, object]:
    return fields.model_dump()


@router.get("/persona")
async def get_current() -> dict[str, object]:
    """启用中的人格版本。"""
    persona = await current_persona()
    return R.ok({"version": persona.version, "fields": _serialize_fields(persona.fields)}).to_dict()


@router.put("/persona")
async def save(request: SavePersonaRequest) -> dict[str, object]:
    """保存人格为新版本（自动启用）。"""
    version = await create_persona(request.fields)
    return R.ok({"version": version}).to_dict()


@router.get("/persona/versions")
async def versions() -> dict[str, object]:
    """全部版本（倒序）。"""
    rows = await list_versions()
    return R.ok(
        {
            "versions": [
                {
                    "version": row.version,
                    "active": row.active,
                    "created_at": row.created_at.isoformat(),
                    "fields": row.fields,
                }
                for row in rows
            ]
        }
    ).to_dict()


@router.get("/persona/versions/{version}")
async def version_detail(version: int) -> dict[str, object]:
    """单版本快照。"""
    persona = await get_version(version)
    return R.ok({"version": persona.version, "fields": _serialize_fields(persona.fields)}).to_dict()


@router.post("/persona/versions/{version}/activate")
async def activate(version: int) -> dict[str, object]:
    """启用指定版本：只影响新会话。"""
    await activate_version(version)
    return R.ok({"version": version}).to_dict()
