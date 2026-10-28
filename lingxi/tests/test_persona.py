"""人格：默认八字段、系统提示词渲染、版本递增与快照不可变。"""

import pytest
from conftest import db_up

from lingxi.db.engine import session_scope
from lingxi.db.repo.persona_repo import PersonaRepo
from lingxi.domain.persona.engine import create_persona, current_persona, render_system_prompt
from lingxi.domain.persona.models import DEFAULT_FIELDS, HARD_LIMITS, Persona, PersonaFields


def test_default_fields_are_cola() -> None:
    """默认人格为可乐，八字段齐备。"""
    assert DEFAULT_FIELDS.name == "可乐"
    assert len(PersonaFields.model_fields) == 8


def test_render_system_prompt_contains_fields_and_limits() -> None:
    """系统提示词包含八字段内容与全部硬底线。"""
    prompt = render_system_prompt(Persona(fields=DEFAULT_FIELDS, version=1))
    assert "可乐" in prompt
    assert "老板" in prompt
    assert "明白" in prompt
    for limit in HARD_LIMITS:
        assert limit in prompt


async def test_version_increment_and_snapshot_immutable(engine_fixture: None) -> None:
    """新版本递增；旧版本快照内容保持不变。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres 再跑 alembic upgrade head")
    before = await current_persona()
    new_version = await create_persona(DEFAULT_FIELDS.model_copy(update={"name": f"改名{before.version + 1}"}))
    assert new_version == before.version + 1
    latest = await current_persona()
    assert latest.fields.name == f"改名{before.version + 1}"
    async with session_scope() as s:
        old = await PersonaRepo(s).get_by_version(before.version)
    assert old is not None
    assert old.fields["name"] == before.fields.name
