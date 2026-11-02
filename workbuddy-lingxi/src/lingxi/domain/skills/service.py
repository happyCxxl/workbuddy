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


@dataclass
class SkillRoot:
    """一个已加载的技能根目录；prefix 用于 nested 命名空间。"""

    path: Path
    source: str
    prefix: str


# 每个会话记住启动时存在的根目录；新顶级目录要通过 reload 才加入
_SESSION_ROOTS: dict[int, list[SkillRoot]] = {}
_MAX_SESSIONS = 200


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


def _read_skill(directory: Path, prefix: str, source: str) -> Skill | None:
    """读取一个 SKILL.md；缺少必要字段时跳过。"""
    path = directory / "SKILL.md"
    try:
        meta, body = _parse(path.read_text(encoding="utf-8", errors="replace"))
    except OSError:
        return None
    name = meta.get("name") or directory.name
    if not name or not body:
        return None
    if meta.get("user-invocable", "true").lower() == "false":
        return None
    display = f"{prefix}:{name}" if prefix else name
    return Skill(
        name=display,
        description=meta.get("description", ""),
        args_hint=meta.get("argument-hint", ""),
        body=body,
        source=source,
    )


def _scan(root: SkillRoot) -> list[Skill]:
    """扫描一个技能根目录下的一级子目录。"""
    if not root.path.is_dir():
        return []
    found: list[Skill] = []
    for child in root.path.iterdir():
        if child.is_dir():
            skill = _read_skill(child, root.prefix, root.source)
            if skill is not None:
                found.append(skill)
    return found


def _discover(work_dir: str) -> list[SkillRoot]:
    """发现启动时存在的根：用户级优先，项目级从近到远。"""
    roots: list[SkillRoot] = [SkillRoot(_user_root(), "user", "")]
    current = Path(work_dir).resolve()
    seen = {roots[0].path.resolve()}
    for parent in [current, *current.parents]:
        candidate = (parent / ".workbuddy" / "skills").resolve()
        if candidate in seen or not candidate.is_dir():
            continue
        seen.add(candidate)
        roots.append(SkillRoot(candidate, "project", ""))
    return roots


def _roots(session_id: int | None, work_dir: str, reload: bool) -> list[SkillRoot]:
    """取会话根目录；reload 时重新发现并加入新顶级目录。"""
    if session_id is None:
        return _discover(work_dir)
    cached = _SESSION_ROOTS.get(session_id)
    if cached is None or reload:
        cached = _discover(work_dir)
        if len(_SESSION_ROOTS) >= _MAX_SESSIONS:
            _SESSION_ROOTS.pop(next(iter(_SESSION_ROOTS)))
        _SESSION_ROOTS[session_id] = cached
    return cached


def list_skills(work_dir: str, session_id: int | None = None, reload: bool = False) -> list[Skill]:
    """用户级优先、项目级其次；启动后新顶级目录需 reload。"""
    result: dict[str, Skill] = {}
    for root in _roots(session_id, work_dir, reload):
        for skill in _scan(root):
            result.setdefault(skill.name, skill)
    return sorted(result.values(), key=lambda item: item.name)


def find_skill(work_dir: str, name: str, session_id: int | None = None) -> Skill | None:
    """按名称取技能：用于执行时加载正文。"""
    return next((skill for skill in list_skills(work_dir, session_id) if skill.name == name), None)


def ensure_nested_root(session_id: int, work_dir: str, target: str) -> None:
    """文件工具访问子目录时，懒加载其 .workbuddy/skills；前置目录变化不改根集合。"""
    cached = _SESSION_ROOTS.get(session_id)
    if cached is None:
        cached = _discover(work_dir)
        _SESSION_ROOTS[session_id] = cached
    base = Path(work_dir).resolve()
    path = Path(target).resolve()
    if path.is_file():
        path = path.parent
    for parent in [path, *path.parents]:
        if parent == base or not parent.is_relative_to(base):
            break
        candidate = parent / ".workbuddy" / "skills"
        if candidate.is_dir():
            prefix = parent.relative_to(base).as_posix()
            if all(root.path != candidate.resolve() for root in cached):
                cached.append(SkillRoot(candidate.resolve(), "project", prefix))
            break
