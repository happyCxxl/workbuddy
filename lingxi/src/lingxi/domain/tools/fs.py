"""文件系统工具：读/列/搜/写/删；所有路径强制限定会话工作目录内，越界拒绝。"""

import os
from pathlib import Path

from pydantic import BaseModel

from lingxi.domain.tools.registry import RISK_HIGH, RISK_READ, RISK_WRITE, ToolContext, ToolDefinition, ToolRegistry

MAX_READ_CHARS = 100_000
MAX_SEARCH_FILES = 500
MAX_SEARCH_HITS = 200
MAX_FILE_CHARS = 200_000


class ReadArgs(BaseModel):
    """读取参数。"""

    path: str


class ListArgs(BaseModel):
    """列目录参数。"""

    path: str = "."


class SearchArgs(BaseModel):
    """搜索参数。"""

    pattern: str
    path: str = "."


class WriteArgs(BaseModel):
    """写入参数。"""

    path: str
    content: str


class DeleteArgs(BaseModel):
    """删除参数。"""

    path: str


def _resolve(work_dir: str, path: str) -> Path:
    """解析路径并强制限定工作目录内；越界抛错。"""
    base = Path(work_dir).resolve()
    target = (base / path).resolve()
    if not target.is_relative_to(base):
        raise ValueError(f"路径越界：{path}")
    return target


async def _fs_read(args: dict[str, object], ctx: ToolContext) -> str:
    target = _resolve(ctx.work_dir, str(args["path"]))
    if not target.is_file():
        raise FileNotFoundError(f"文件不存在：{target}")
    content = target.read_text(encoding="utf-8", errors="replace")
    if len(content) > MAX_READ_CHARS:
        return content[:MAX_READ_CHARS] + "\n…（已截断）"
    return content


async def _fs_list(args: dict[str, object], ctx: ToolContext) -> str:
    target = _resolve(ctx.work_dir, str(args["path"]))
    if not target.is_dir():
        raise NotADirectoryError(f"不是目录：{target}")
    lines: list[str] = []
    for child in sorted(target.iterdir()):
        if child.is_dir():
            lines.append(f"d {child.name}")
        else:
            lines.append(f"f {child.name} {child.stat().st_size}")
    return "\n".join(lines) or "（空目录）"


async def _fs_search(args: dict[str, object], ctx: ToolContext) -> str:
    base = _resolve(ctx.work_dir, str(args.get("path", ".")))
    pattern = str(args["pattern"])
    hits: list[str] = []
    files_scanned = 0
    for root, _dirs, files in os.walk(base):
        for name in files:
            if files_scanned >= MAX_SEARCH_FILES:
                break
            files_scanned += 1
            file_path = Path(root) / name
            try:
                content = file_path.read_text(encoding="utf-8", errors="replace")[:MAX_FILE_CHARS]
            except OSError:
                continue
            for lineno, line in enumerate(content.splitlines(), start=1):
                if pattern in line:
                    hits.append(f"{file_path}:{lineno}:{line[:200]}")
                if len(hits) >= MAX_SEARCH_HITS:
                    break
    return "\n".join(hits) or "未命中"


async def _fs_write(args: dict[str, object], ctx: ToolContext) -> str:
    target = _resolve(ctx.work_dir, str(args["path"]))
    content = str(args["content"])
    target.write_text(content, encoding="utf-8")
    return f"已写入 {len(content)} 字符 → {target}"


async def _fs_delete(args: dict[str, object], ctx: ToolContext) -> str:
    target = _resolve(ctx.work_dir, str(args["path"]))
    if not target.exists():
        raise FileNotFoundError(f"文件不存在：{target}")
    if target.is_dir():
        raise IsADirectoryError(f"目录不支持删除：{target}")
    target.unlink()
    return f"已删除 {target}"


def register_fs_tools(registry: ToolRegistry) -> None:
    """注册文件系统五件套：写覆盖已有与删除为高危，裁决在权限门。"""
    registry.register(
        ToolDefinition(
            name="fs_read",
            description="读取工作目录内的文本文件（限定工作目录内）",
            parameters=ReadArgs.model_json_schema(),
            risk=RISK_READ,
            handler=_fs_read,
        )
    )
    registry.register(
        ToolDefinition(
            name="fs_list",
            description="列出工作目录内某目录的内容",
            parameters=ListArgs.model_json_schema(),
            risk=RISK_READ,
            handler=_fs_list,
        )
    )
    registry.register(
        ToolDefinition(
            name="fs_search",
            description="在工作目录内按子串搜索文本内容，返回 文件:行号:内容",
            parameters=SearchArgs.model_json_schema(),
            risk=RISK_READ,
            handler=_fs_search,
        )
    )
    registry.register(
        ToolDefinition(
            name="fs_write",
            description="写文本文件（覆盖已有内容属高危操作）",
            parameters=WriteArgs.model_json_schema(),
            risk=RISK_WRITE,
            handler=_fs_write,
        )
    )
    registry.register(
        ToolDefinition(
            name="fs_delete",
            description="删除文件（高危操作）",
            parameters=DeleteArgs.model_json_schema(),
            risk=RISK_HIGH,
            handler=_fs_delete,
        )
    )
