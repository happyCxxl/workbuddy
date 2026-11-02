"""技能目录：发现 SKILL.md，解析元数据并读取正文。"""

import os
from dataclasses import dataclass
from pathlib import Path


@dataclass
class Skill:
    """一个可用技能：名称、描述、参数提示与正文。"""

    name: str
    description: str
    args_hint: str
    body: str
    source: str


def _user_root() -> Path:
    """用户级技能目录。"""
    configured = os.environ.get("WORKBUDDY_SKILLS_DIR")
    return Path(configured) if configured else Path.home() / ".workbuddy" / "skills"


def _parse(text: str) -> tuple[dict[str, str], str]:
    """解析 YAML frontmatter 的简单键值，返回元数据与正文。"""
    lines = text.splitlines()
    if not lines or lines[0].strip() != "---":
        return {}, text
    meta: dict[str, str] = {}
    index = 1
    while index < len(lines) and lines[index].strip() != "---":
        line = lines[index]
        if ":" in line and not line.lstrip().startswith("-"):
            key, value = line.split(":", 1)
            meta[key.strip()] = value.strip().strip('"').strip("'")
        index += 1
    body = "\n".join(lines[index + 1 :]).strip()
    return meta, body


def _read_skill(path: Path, source: str) -> Skill | None:
    """读取一个 SKILL.md；缺少必要字段时跳过。"""
    try:
        meta, body = _parse(path.read_text(encoding="utf-8", errors="replace"))
    except OSError:
        return None
    name = meta.get("name") or path.parent.name
    if not name or not body:
        return None
    if meta.get("user-invocable", "true").lower() == "false":
        return None
    return Skill(
        name=name,
        description=meta.get("description", ""),
        args_hint=meta.get("argument-hint", ""),
        body=body,
        source=source,
    )


def _scan(root: Path, source: str) -> list[Skill]:
    """扫描一个 skills 根目录下的一级子目录。"""
    if not root.is_dir():
        return []
    found: list[Skill] = []
    for child in root.iterdir():
        skill_file = child / "SKILL.md"
        if child.is_dir() and skill_file.is_file():
            skill = _read_skill(skill_file, source)
            if skill is not None:
                found.append(skill)
    return found


def list_skills(work_dir: str) -> list[Skill]:
    """用户级优先、项目级其次；同名只保留优先级高的一份。"""
    roots: list[tuple[Path, str]] = [(_user_root(), "user")]
    current = Path(work_dir).resolve()
    for parent in [current, *current.parents]:
        candidate = parent / ".workbuddy" / "skills"
        if candidate.is_dir() and candidate != _user_root().resolve():
            roots.append((candidate, "project"))
    result: dict[str, Skill] = {}
    for root, source in roots:
        for skill in _scan(root, source):
            result.setdefault(skill.name, skill)
    return sorted(result.values(), key=lambda item: item.name)


def find_skill(work_dir: str, name: str) -> Skill | None:
    """按名称取技能：用于执行时加载正文。"""
    return next((skill for skill in list_skills(work_dir) if skill.name == name), None)
