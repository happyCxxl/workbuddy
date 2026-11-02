"""技能目录：发现、优先级与会话接口。"""

import pytest
from httpx import AsyncClient

from lingxi.domain.session.service import create_session
from lingxi.domain.skills.service import find_skill, list_skills


def _write_skill(root, name: str, description: str, body: str) -> None:
    """写入一个最小 SKILL.md。"""
    directory = root / name
    directory.mkdir(parents=True)
    (directory / "SKILL.md").write_text(
        f'---\nname: {name}\ndescription: {description}\nargument-hint: "[path]"\n---\n{body}',
        encoding="utf-8",
    )


def test_skill_precedence_and_parse(tmp_path, monkeypatch) -> None:
    """用户级覆盖项目级；frontmatter 解析出描述与参数提示。"""
    user_root = tmp_path / "user-skills"
    monkeypatch.setenv("WORKBUDDY_SKILLS_DIR", str(user_root))
    work = tmp_path / "work"
    project_root = work / ".workbuddy" / "skills"
    _write_skill(user_root, "review", "用户级审查", "用户级正文")
    _write_skill(project_root, "review", "项目级审查", "项目级正文")
    _write_skill(project_root, "deploy", "部署", "部署正文")

    skills = list_skills(str(work))

    assert [skill.name for skill in skills] == ["deploy", "review"]
    review = find_skill(str(work), "review")
    assert review is not None
    assert review.description == "用户级审查"
    assert review.args_hint == "[path]"
    assert review.source == "user"


@pytest.mark.usefixtures("engine_fixture")
async def test_session_skills_api(client: AsyncClient, tmp_path, monkeypatch) -> None:
    """会话接口返回当前工作区可用 skill。"""
    monkeypatch.setenv("WORKBUDDY_SKILLS_DIR", str(tmp_path / "user-skills"))
    work = tmp_path / "work"
    work.mkdir()
    _write_skill(work / ".workbuddy" / "skills", "review", "审查改动", "正文")
    session_id = await create_session(str(work), "技能")

    response = await client.get(f"/sessions/{session_id}/skills")

    assert response.json()["code"] == 0
    skills = response.json()["data"]["skills"]
    assert skills == [
        {
            "name": "review",
            "description": "审查改动",
            "args_hint": "[path]",
            "source": "project",
        }
    ]
