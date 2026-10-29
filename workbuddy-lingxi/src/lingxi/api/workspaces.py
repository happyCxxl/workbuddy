"""工作区接口：列表、创建、重命名与删除（会话归属工作区，DSH workspace 语义）。"""

from fastapi import APIRouter
from pydantic import BaseModel

from lingxi.contract import R
from lingxi.domain.workspace.service import (
    create_workspace,
    delete_workspace,
    list_workspaces,
    rename_workspace,
)

router = APIRouter()


class CreateWorkspaceRequest(BaseModel):
    """创建工作区请求：目录必填，名称缺省取目录名。"""

    path: str
    name: str = ""


class RenameWorkspaceRequest(BaseModel):
    """重命名工作区请求：只改显示名，路径不变。"""

    name: str


def serialize_workspace(row: object) -> dict[str, object]:
    """工作区序列化：ID 一律字符串。"""
    from lingxi.db.models import Workspace

    assert isinstance(row, Workspace)
    return {
        "id": str(row.id),
        "name": row.name,
        "path": row.path,
        "created_at": row.created_at.isoformat(),
    }


@router.get("/workspaces")
async def workspaces() -> dict[str, object]:
    """全部工作区：新建在前。"""
    rows = await list_workspaces()
    return R.ok({"workspaces": [serialize_workspace(row) for row in rows]}).to_dict()


@router.post("/workspaces")
async def create(request: CreateWorkspaceRequest) -> dict[str, object]:
    """创建工作区：目录必须存在，路径唯一。"""
    row = await create_workspace(request.path, request.name)
    return R.ok(serialize_workspace(row)).to_dict()


@router.patch("/workspaces/{workspace_id}")
async def rename(workspace_id: int, request: RenameWorkspaceRequest) -> dict[str, object]:
    """重命名工作区：只改显示名；目录与会话归属不变。"""
    row = await rename_workspace(workspace_id, request.name)
    return R.ok(serialize_workspace(row)).to_dict()


@router.delete("/workspaces/{workspace_id}")
async def remove(workspace_id: int) -> dict[str, object]:
    """删除工作区：其下会话一并逻辑删除（只删记录，磁盘目录不动）。"""
    deleted = await delete_workspace(workspace_id)
    return R.ok({"deleted": deleted}).to_dict()
