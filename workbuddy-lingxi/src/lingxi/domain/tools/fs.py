"""文件系统工具：读/列/搜/glob/写/编辑/删；路径强制限定工作目录，已有文件“先读后写/改”。"""

import fnmatch
import os
import re
from pathlib import Path

from pydantic import BaseModel, Field

from lingxi.domain.tools.registry import (
    RISK_HIGH,
    RISK_READ,
    RISK_WRITE,
    ToolContext,
    ToolDefinition,
    ToolRegistry,
    parse_int,
)

MAX_READ_CHARS = 100_000
MAX_READ_LINES = 2000
MAX_SEARCH_FILES = 500
MAX_SEARCH_HITS = 200
MAX_FILE_CHARS = 200_000
MAX_LIST_ENTRIES = 500
MAX_GLOB_RESULTS = 500


class ReadArgs(BaseModel):
    """读取参数。"""

    path: str = Field(description="文件路径，相对工作目录（如 src/main.py）；也可传绝对路径，读工作区外会先征得同意")
    offset: int = Field(default=1, ge=1, description="起始行号（1 起），用于分段读大文件")
    limit: int = Field(default=MAX_READ_LINES, ge=1, le=MAX_READ_LINES, description="本次最多读取行数")


class ListArgs(BaseModel):
    """列目录参数。"""

    path: str = Field(
        default=".", description="目录路径，相对工作目录；也可传绝对路径（读工作区外需同意）；默认 . 表示工作目录根"
    )


class SearchArgs(BaseModel):
    """搜索参数。"""

    pattern: str = Field(description="搜索模式：默认按字面子串匹配（区分大小写）；regex=true 时按正则匹配")
    path: str = Field(default=".", description="搜索起点目录，相对工作目录；也可传绝对路径（读工作区外需同意）；默认 .")
    regex: bool = Field(default=False, description="是否把 pattern 当作正则表达式")
    glob: str = Field(default="", description="只搜索文件名匹配该 glob 的文件（如 *.py），空表示全部")


class GlobArgs(BaseModel):
    """文件匹配参数。"""

    pattern: str = Field(description="文件名 glob 模式，相对 path（如 **/*.py、src/*.ts）")
    path: str = Field(default=".", description="搜索起点目录，相对工作目录；也可传绝对路径（读工作区外需同意）；默认 .")


class WriteArgs(BaseModel):
    """写入参数。"""

    path: str = Field(description="写入的文件路径，相对工作目录（只能在工作区内）；父目录不存在会自动创建")
    content: str = Field(description="完整文件内容（UTF-8 文本，覆盖写入，不是追加）")


class EditArgs(BaseModel):
    """编辑参数。"""

    path: str = Field(description="要编辑的文件路径，相对工作目录")
    old_string: str = Field(description="要被替换的原文（必须与文件内容完全一致，含缩进/换行）")
    new_string: str = Field(description="替换后的新文本（空串表示删除该段）")
    replace_all: bool = Field(default=False, description="old_string 出现多次时是否全部替换；默认只允许唯一匹配")


class DeleteArgs(BaseModel):
    """删除参数。"""

    path: str = Field(description="要删除的单个文件路径，相对工作目录；不支持删除目录")


def _resolve(work_dir: str, path: str, *, write: bool = False, allow_outside: bool = False) -> Path:
    """解析路径：读允许工作区外（越界需权限审批），写默认限工作区，完全权限档可越界写。"""
    base = Path(work_dir).resolve()
    target = Path(path)
    if not target.is_absolute():
        target = base / path
    target = target.resolve()
    if write and not allow_outside and not target.is_relative_to(base):
        raise ValueError(f"写操作只能在工作区内：{path}（读可以用绝对路径，越界读会先征得同意）")
    return target


def _require_read(ctx: ToolContext, target: Path, action: str) -> None:
    """读前写/改门禁：已有文件必须先 fs_read 再修改（无会话状态时跳过，兼容直接单测）。"""
    if ctx.state is None:
        return
    if target.exists() and str(target) not in ctx.state.read_files:
        raise PermissionError(f"{action}已有文件前必须先用 fs_read 读取：{target.name}（可分段读，用 offset/limit）")


def _mark_read(ctx: ToolContext, target: Path) -> None:
    """记录会话已读文件。"""
    if ctx.state is not None:
        ctx.state.read_files.add(str(target))


async def _fs_read(args: dict[str, object], ctx: ToolContext) -> str:
    target = _resolve(ctx.work_dir, str(args["path"]))
    if target.is_dir():
        raise IsADirectoryError(f"不是文件而是目录：{args['path']}（查看目录内容请用 fs_list）")
    if not target.is_file():
        raise FileNotFoundError(f"文件不存在：{args['path']}（先用 fs_list 确认目录内容，或用 fs_search 搜索内容）")
    content = target.read_text(encoding="utf-8", errors="replace")
    _mark_read(ctx, target)
    lines = content.splitlines()
    total = len(lines)
    if total == 0:
        return "（空文件）"
    offset = max(1, parse_int(args.get("offset"), 1))
    limit = max(1, min(parse_int(args.get("limit"), MAX_READ_LINES), MAX_READ_LINES))
    start = min(offset, total)
    end = min(start + limit - 1, total)
    text = "\n".join(f"{lineno:6d}| {lines[lineno - 1]}" for lineno in range(start, end + 1))
    if len(text) > MAX_READ_CHARS:
        return text[:MAX_READ_CHARS] + f"\n…（已截断，仅显示前 {MAX_READ_CHARS} 字符；可减小 limit 或分段读）"
    if end < total:
        text += f"\n…（文件共 {total} 行，已显示 {start}-{end}；继续读请用 offset={end + 1}）"
    return text


async def _fs_list(args: dict[str, object], ctx: ToolContext) -> str:
    target = _resolve(ctx.work_dir, str(args["path"]))
    if not target.is_dir():
        raise NotADirectoryError(f"不是目录：{args['path']}（列文件所在目录，或改用 fs_read 读文件）")
    lines: list[str] = []
    for child in sorted(target.iterdir()):
        if len(lines) >= MAX_LIST_ENTRIES:
            lines.append(f"…（仅显示前 {MAX_LIST_ENTRIES} 项）")
            break
        try:
            if child.is_dir():
                lines.append(f"d {child.name}")
            else:
                lines.append(f"f {child.name} {child.stat().st_size}")
        except OSError:
            continue
    return "\n".join(lines) or "（空目录）"


async def _fs_glob(args: dict[str, object], ctx: ToolContext) -> str:
    base = _resolve(ctx.work_dir, str(args.get("path", ".")))
    if not base.is_dir():
        raise NotADirectoryError(f"搜索起点不是目录：{args.get('path', '.')}（path 需指向目录）")
    pattern = str(args["pattern"]).strip()
    if not pattern:
        raise ValueError("pattern 不能为空（示例：**/*.py）")
    try:
        matches = sorted(path for path in base.glob(pattern) if path.is_file())
    except (ValueError, NotImplementedError, OSError) as exc:
        raise ValueError(f"无效的 glob 模式：{pattern}（示例：**/*.py；模式相对 path，不能是绝对路径）") from exc
    lines: list[str] = []
    for path in matches:
        try:
            lines.append(path.relative_to(base).as_posix())
        except ValueError:
            continue
        if len(lines) >= MAX_GLOB_RESULTS:
            lines.append(f"…（仅显示前 {MAX_GLOB_RESULTS} 个文件；请缩小 pattern 或 path）")
            break
    if not lines:
        return f"未匹配到文件（pattern={pattern}，path={args.get('path', '.')}；确认模式与路径）"
    return "\n".join(lines)


async def _fs_search(args: dict[str, object], ctx: ToolContext) -> str:
    base = _resolve(ctx.work_dir, str(args.get("path", ".")))
    if not base.is_dir():
        raise NotADirectoryError(f"搜索起点不是目录：{args.get('path', '.')}（path 需指向目录）")
    pattern = str(args["pattern"])
    regex = bool(args.get("regex"))
    file_glob = str(args.get("glob") or "").strip()
    compiled: re.Pattern[str] | None = None
    if regex:
        try:
            compiled = re.compile(pattern)
        except re.error as exc:
            raise ValueError(f"无效的正则：{pattern}（{exc}；改用 regex=false 可按字面子串搜索）") from exc
    hits: list[str] = []
    files_scanned = 0
    files_truncated = False
    for root, _dirs, files in os.walk(base):
        if files_scanned >= MAX_SEARCH_FILES:
            files_truncated = True
            break
        for name in files:
            if files_scanned >= MAX_SEARCH_FILES:
                files_truncated = True
                break
            if file_glob and not fnmatch.fnmatch(name, file_glob):
                continue
            files_scanned += 1
            file_path = Path(root) / name
            try:
                content = file_path.read_text(encoding="utf-8", errors="replace")[:MAX_FILE_CHARS]
            except OSError:
                continue
            for lineno, line in enumerate(content.splitlines(), start=1):
                matched = compiled.search(line) is not None if compiled is not None else pattern in line
                if matched:
                    hits.append(f"{file_path}:{lineno}:{line[:200]}")
                if len(hits) >= MAX_SEARCH_HITS:
                    break
            if len(hits) >= MAX_SEARCH_HITS:
                break
        if len(hits) >= MAX_SEARCH_HITS:
            break
    if not hits:
        return f"未命中（已扫描 {files_scanned} 个文件）"
    notes: list[str] = []
    if len(hits) >= MAX_SEARCH_HITS:
        notes.append(f"命中过多，仅显示前 {MAX_SEARCH_HITS} 条；可缩小 path、加 glob 过滤或换更具体的模式")
    if files_truncated:
        notes.append(f"仅扫描前 {MAX_SEARCH_FILES} 个文件；可缩小 path 后重试")
    return "\n".join([*hits, *notes]) if notes else "\n".join(hits)


async def _fs_write(args: dict[str, object], ctx: ToolContext) -> str:
    target = _resolve(ctx.work_dir, str(args["path"]), write=True, allow_outside=ctx.full_access)
    _require_read(ctx, target, "写入")
    content = str(args["content"])
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")
    _mark_read(ctx, target)
    return f"已写入 {len(content)} 字符 → {args['path']}（覆盖写入；可回读校验）"


async def _fs_edit(args: dict[str, object], ctx: ToolContext) -> str:
    target = _resolve(ctx.work_dir, str(args["path"]), write=True, allow_outside=ctx.full_access)
    if not target.is_file():
        raise FileNotFoundError(f"文件不存在：{args['path']}（编辑前先用 fs_read 读取内容）")
    _require_read(ctx, target, "编辑")
    old_string = str(args["old_string"])
    new_string = str(args["new_string"])
    replace_all = bool(args.get("replace_all"))
    if not old_string:
        raise ValueError("old_string 不能为空（要删除内容请给定唯一原文）")
    content = target.read_text(encoding="utf-8", errors="replace")
    count = content.count(old_string)
    if count == 0:
        raise ValueError("未找到 old_string；请先用 fs_read 核对原文（必须含缩进/换行，完全一致）")
    if count > 1 and not replace_all:
        raise ValueError(f"old_string 出现 {count} 次，不唯一；请提供更多上下文使其唯一，或设置 replace_all=true")
    updated = content.replace(old_string, new_string) if replace_all else content.replace(old_string, new_string, 1)
    target.write_text(updated, encoding="utf-8")
    _mark_read(ctx, target)
    return f"已替换 {count if replace_all else 1} 处 → {args['path']}；可回读校验"


async def _fs_delete(args: dict[str, object], ctx: ToolContext) -> str:
    target = _resolve(ctx.work_dir, str(args["path"]), write=True, allow_outside=ctx.full_access)
    if target.is_dir():
        raise IsADirectoryError(f"是目录：{args['path']}（fs_delete 只删文件；列目录请用 fs_list）")
    if not target.exists():
        raise FileNotFoundError(f"文件不存在：{args['path']}（先用 fs_list 确认文件名）")
    target.unlink()
    return f"已删除 {args['path']}"


def register_fs_tools(registry: ToolRegistry) -> None:
    """注册文件系统工具：写覆盖/编辑为写级别，删除为高危；裁决在权限门。"""
    registry.register(
        ToolDefinition(
            name="fs_read",
            description=(
                "读取工作目录内文本文件，输出带行号。默认最多 2000 行；大文件用 offset/limit 分段读。"
                "查看目录内容请用 fs_list；编辑/覆盖已有文件前必须先读。"
            ),
            parameters=ReadArgs.model_json_schema(),
            risk=RISK_READ,
            handler=_fs_read,
            path_fields=("path",),
        )
    )
    registry.register(
        ToolDefinition(
            name="fs_list",
            description=(
                "列出工作目录内某目录的直接子项（d 目录 / f 文件 + 字节数），不递归。"
                "找文件请用 fs_glob；读写文件前先用它确认路径。"
            ),
            parameters=ListArgs.model_json_schema(),
            risk=RISK_READ,
            handler=_fs_list,
            path_fields=("path",),
        )
    )
    registry.register(
        ToolDefinition(
            name="fs_glob",
            description=(
                "按 glob 模式查找文件（如 **/*.py、src/*.ts），返回相对工作目录的路径。"
                "找文件优先用它，而不是 fs_list 逐层翻或 shell dir。"
            ),
            parameters=GlobArgs.model_json_schema(),
            risk=RISK_READ,
            handler=_fs_glob,
            path_fields=("path",),
        )
    )
    registry.register(
        ToolDefinition(
            name="fs_search",
            description=(
                "搜索工作目录内文本文件的内容，返回 文件:行号:内容。"
                "默认字面子串；regex=true 用正则；glob=*.py 可只搜指定类型；最多扫描 500 文件、返回 200 条。"
            ),
            parameters=SearchArgs.model_json_schema(),
            risk=RISK_READ,
            handler=_fs_search,
            path_fields=("path",),
        )
    )
    registry.register(
        ToolDefinition(
            name="fs_write",
            description=(
                "写入文本文件（UTF-8，整文件覆盖，不是追加）：父目录不存在会自动创建；"
                "覆盖已有文件前必须先 fs_read；覆盖属高危操作会先询问用户。修改已有文件优先用 fs_edit。"
            ),
            parameters=WriteArgs.model_json_schema(),
            risk=RISK_WRITE,
            handler=_fs_write,
            path_fields=("path",),
        )
    )
    registry.register(
        ToolDefinition(
            name="fs_edit",
            description=(
                "精确字符串替换编辑文件：old_string 必须与原文完全一致（含缩进/换行）且唯一；"
                "多次出现时用 replace_all=true。编辑前必须先 fs_read。优先用它改已有文件，而不是 fs_write 整文件覆盖。"
            ),
            parameters=EditArgs.model_json_schema(),
            risk=RISK_WRITE,
            handler=_fs_edit,
            path_fields=("path",),
        )
    )
    registry.register(
        ToolDefinition(
            name="fs_delete",
            description="删除单个文件（高危操作，需用户确认）；不支持删除目录，路径限定工作目录内。",
            parameters=DeleteArgs.model_json_schema(),
            risk=RISK_HIGH,
            handler=_fs_delete,
            path_fields=("path",),
        )
    )
