"""目录浏览接口：新建会话时选择工作目录（只读列目录，不建、不改、不删）。"""

import asyncio
import os
from pathlib import Path

from fastapi import APIRouter

from lingxi.contract import ApiError, ErrorCode, R

router = APIRouter()


def _scan(path: str) -> dict[str, object]:
    """同步列目录（放线程池执行，避免阻塞事件循环）。"""
    base = Path(path).resolve()
    if not base.is_dir():
        raise ApiError(ErrorCode.NOT_FOUND, f"目录不存在：{path}")
    entries: list[dict[str, str]] = []
    try:
        for child in sorted(base.iterdir(), key=lambda item: item.name.lower()):
            if child.is_dir():
                entries.append({"name": child.name, "path": str(child)})
    except PermissionError as exc:
        raise ApiError(ErrorCode.INVALID_ARGUMENT, f"目录不可读：{path}") from exc
    parent = str(base.parent) if base.parent != base else None
    return {"path": str(base), "parent": parent, "entries": entries}


@router.get("/fs/dirs")
async def list_dirs(path: str | None = None) -> dict[str, object]:
    """列目录：不带 path 时返回本机盘符；带 path 时返回该目录的子目录与上级路径。"""
    if not path:
        roots = os.listdrives() if hasattr(os, "listdrives") else ["/"]
        return R.ok({"path": "", "parent": None, "entries": [{"name": root, "path": root} for root in roots]}).to_dict()
    return R.ok(await asyncio.to_thread(_scan, path)).to_dict()
