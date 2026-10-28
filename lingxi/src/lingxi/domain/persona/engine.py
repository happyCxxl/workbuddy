"""人格引擎：版本管理与系统提示词渲染。"""

from lingxi.contract import ApiError, ErrorCode
from lingxi.db.engine import session_scope
from lingxi.db.models import PersonaVersion
from lingxi.db.repo.persona_repo import PersonaRepo
from lingxi.domain.persona.models import HARD_LIMITS, Persona, PersonaFields


async def current_persona() -> Persona:
    """当前最新版本人格。"""
    async with session_scope() as s:
        row = await PersonaRepo(s).latest()
    if row is None:
        raise ApiError(ErrorCode.NOT_FOUND, "人格未初始化：先执行 alembic upgrade head")
    return _to_persona(row)


async def create_persona(fields: PersonaFields) -> int:
    """保存新版本：版本递增，旧版本快照不变。"""
    async with session_scope() as s:
        repo = PersonaRepo(s)
        version = await repo.max_version() + 1
        await repo.insert(PersonaVersion(version=version, fields=fields.model_dump()))
        await s.commit()
        return version


def render_system_prompt(persona: Persona) -> str:
    """渲染系统提示词：八字段 + 硬底线。"""
    fields = persona.fields
    lines = [
        f"你是「{fields.name}」，{fields.user_name} 的 AI 助理。",
        f"开场白：{fields.opening}",
        f"口癖：{'、'.join(fields.catchphrases)}",
        f"语气：{fields.tone}",
        f"回应风格：{fields.style}",
        f"边界：{fields.boundaries}",
        f"用户画像：{fields.user_profile or '暂无'}",
        "硬底线（不可违背）：",
        *(f"- {limit}" for limit in HARD_LIMITS),
    ]
    return "\n".join(lines)


def _to_persona(row: PersonaVersion) -> Persona:
    return Persona(fields=PersonaFields.model_validate(row.fields), version=row.version)
