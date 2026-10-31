"""人格引擎：按用户管理版本（新版本自动启用）、启用切换与系统提示词渲染。"""

from sqlalchemy.ext.asyncio import AsyncSession

from lingxi.contract import ApiError, ErrorCode
from lingxi.core.prompts import render_prompt
from lingxi.db.engine import session_scope
from lingxi.db.models import PersonaVersion
from lingxi.db.repo.persona_repo import PersonaRepo
from lingxi.domain.persona.models import DEFAULT_FIELDS, HARD_LIMITS, Persona, PersonaFields


async def _ensure_current(s: AsyncSession, user_id: int) -> PersonaVersion:
    """取启用版本；该用户从未建过人格时按默认字段懒创建 v1。"""
    repo = PersonaRepo(s)
    row = await repo.current(user_id)
    if row is not None:
        return row
    row = PersonaVersion(user_id=user_id, version=1, fields=DEFAULT_FIELDS.model_dump(), active=True)
    await repo.insert(row)
    await s.commit()
    return row


async def current_persona(user_id: int = 1) -> Persona:
    """该用户启用中的人格版本；没有则懒创建默认人格。"""
    async with session_scope() as s:
        row = await _ensure_current(s, user_id)
    return _to_persona(row)


async def get_version(version: int, user_id: int = 1) -> Persona:
    """按版本读当前用户的快照。"""
    async with session_scope() as s:
        row = await PersonaRepo(s).get_by_version(user_id, version)
    if row is None:
        raise ApiError(ErrorCode.NOT_FOUND, f"人格版本不存在：{version}")
    return _to_persona(row)


async def list_versions(user_id: int = 1) -> list[PersonaVersion]:
    """该用户全部版本（倒序）。"""
    async with session_scope() as s:
        return await PersonaRepo(s).list_all(user_id)


async def create_persona(fields: PersonaFields, user_id: int = 1) -> int:
    """保存新版本：版本递增、旧快照不变，新版本自动启用。"""
    async with session_scope() as s:
        repo = PersonaRepo(s)
        version = await repo.max_version(user_id) + 1
        await repo.insert(PersonaVersion(user_id=user_id, version=version, fields=fields.model_dump()))
        await repo.set_active(user_id, version)
        await s.commit()
        return version


async def activate_version(version: int, user_id: int = 1) -> None:
    """启用该用户的指定版本：只影响新会话，已建会话保持绑定版本。"""
    async with session_scope() as s:
        repo = PersonaRepo(s)
        row = await repo.get_by_version(user_id, version)
        if row is None:
            raise ApiError(ErrorCode.NOT_FOUND, f"人格版本不存在：{version}")
        await repo.set_active(user_id, version)
        await s.commit()


def render_system_prompt(persona: Persona) -> str:
    """渲染系统提示词：模板在 prompts/persona.md（八字段 + 硬底线）。"""
    fields = persona.fields
    return render_prompt(
        "persona",
        name=fields.name,
        user_name=fields.user_name,
        opening=fields.opening,
        catchphrases="、".join(fields.catchphrases),
        tone=fields.tone,
        style=fields.style,
        boundaries=fields.boundaries,
        user_profile=fields.user_profile or "暂无",
        hard_limits="\n".join(f"- {limit}" for limit in HARD_LIMITS),
    )


def _to_persona(row: PersonaVersion) -> Persona:
    return Persona(fields=PersonaFields.model_validate(row.fields), version=row.version)
