"""工作区服务：列表、创建（路径唯一）、重命名与删除。"""

import asyncio
from pathlib import Path

from sqlalchemy import select

from lingxi.contract import ApiError, ErrorCode
from lingxi.db.engine import session_scope
from lingxi.db.models import AiSession, Workspace


def _resolve_directory(path: str) -> Path:
    """同步解析并校验目录（放线程池执行，避免阻塞事件循环）。"""
    resolved = Path(path).expanduser().resolve()
    if not resolved.is_dir():
        raise ApiError(ErrorCode.INVALID_ARGUMENT, f"目录不存在：{path}")
    return resolved


async def list_workspaces() -> list[Workspace]:
    """全部工作区：新建在前。"""
    async with session_scope() as s:
        stmt = select(Workspace).order_by(Workspace.id.desc())
        return list((await s.scalars(stmt)).all())


async def get_workspace(workspace_id: int) -> Workspace:
    """读工作区：不存在按登记错误码失败。"""
    async with session_scope() as s:
        row = await s.get(Workspace, workspace_id)
    if row is None:
        raise ApiError(ErrorCode.NOT_FOUND, f"工作区不存在：{workspace_id}")
    return row


async def create_workspace(path: str, name: str = "") -> Workspace:
    """创建工作区：目录必须存在，路径唯一；名称缺省取目录名。"""
    resolved = await asyncio.to_thread(_resolve_directory, path)
    workspace_name = " ".join(name.split()) or resolved.name or str(resolved)
    async with session_scope() as s:
        existing = await s.scalar(select(Workspace).where(Workspace.path == str(resolved)))
        if existing is not None:
            raise ApiError(ErrorCode.INVALID_ARGUMENT, f"工作区已存在：{resolved}")
        row = Workspace(name=workspace_name[:255], path=str(resolved))
        s.add(row)
        await s.commit()
        return row


async def rename_workspace(workspace_id: int, name: str) -> Workspace:
    """重命名工作区：只改显示名，目录与会话归属不变；空名按参数错误拒绝。"""
    workspace_name = " ".join(name.split())
    if not workspace_name:
        raise ApiError(ErrorCode.INVALID_ARGUMENT, "工作区名称不能为空")
    async with session_scope() as s:
        row = await s.get(Workspace, workspace_id)
        if row is None:
            raise ApiError(ErrorCode.NOT_FOUND, f"工作区不存在：{workspace_id}")
        row.name = workspace_name[:255]
        await s.commit()
        return row


async def delete_workspace(workspace_id: int) -> int:
    """删除工作区：其下会话逻辑删除并解绑，工作区行物理删除；磁盘目录不动。"""
    async with session_scope() as s:
        row = await s.get(Workspace, workspace_id)
        if row is None:
            raise ApiError(ErrorCode.NOT_FOUND, f"工作区不存在：{workspace_id}")
        sessions = list(
            (
                await s.scalars(
                    select(AiSession).where(AiSession.workspace_id == workspace_id, AiSession.status == "active")
                )
            ).all()
        )
        for session in sessions:
            session.status = "deleted"
            session.workspace_id = None
        await s.delete(row)
        await s.commit()
        return len(sessions)
