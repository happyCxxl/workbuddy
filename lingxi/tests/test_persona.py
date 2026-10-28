"""人格：默认八字段、系统提示词渲染、版本递增、启用切换与快照不可变。"""

import pytest
from conftest import db_up

from lingxi.db.engine import session_scope
from lingxi.db.repo.persona_repo import PersonaRepo
from lingxi.domain.persona.engine import (
    activate_version,
    create_persona,
    current_persona,
    list_versions,
    render_system_prompt,
)
from lingxi.domain.persona.models import DEFAULT_FIELDS, HARD_LIMITS, Persona, PersonaFields
from lingxi.domain.session.service import create_session, get_session, persona_of_session


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
    """新版本递增并自动启用；旧版本快照内容保持不变。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres 再跑 alembic upgrade head")
    versions = await list_versions()
    max_before = max(row.version for row in versions)
    before_active = await current_persona()
    new_version = await create_persona(DEFAULT_FIELDS.model_copy(update={"name": f"改名{max_before + 1}"}))
    assert new_version == max_before + 1
    latest = await current_persona()
    assert latest.version == new_version
    assert latest.fields.name == f"改名{max_before + 1}"
    async with session_scope() as s:
        old = await PersonaRepo(s).get_by_version(before_active.version)
    assert old is not None
    assert old.fields["name"] == before_active.fields.name


async def test_activate_old_version_changes_default(engine_fixture: None) -> None:
    """启用旧版本：只影响新会话默认版本，且同时仅一个启用。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres 再跑 alembic upgrade head")
    versions = await list_versions()
    assert versions
    oldest = versions[-1]
    target = await create_persona(DEFAULT_FIELDS.model_copy(update={"name": f"启用测试{oldest.version}"}))
    assert (await current_persona()).version == target
    await activate_version(oldest.version)
    assert (await current_persona()).version == oldest.version
    rows = await list_versions()
    assert [row.version for row in rows if row.active] == [oldest.version]


async def test_new_session_binds_active_version(engine_fixture: None) -> None:
    """新会话绑定启用版本；之后再启用别的版本不影响它。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres 再跑 alembic upgrade head")
    versions = await list_versions()
    oldest = versions[-1]
    await activate_version(oldest.version)
    sid = await create_session("D:\\tmp", "版本绑定")
    session = await get_session(sid)
    bound = await persona_of_session(session)
    assert bound.version == oldest.version
    await create_persona(DEFAULT_FIELDS.model_copy(update={"name": "更新后人格"}))
    assert (await current_persona()).version != oldest.version
    again = await persona_of_session(await get_session(sid))
    assert again.version == oldest.version
