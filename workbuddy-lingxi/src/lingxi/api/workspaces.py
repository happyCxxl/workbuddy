"""工作区接口：列表与创建（会话归属工作区，DSH workspace 语义）。"""

from fastapi import APIRouter
from pydantic import BaseModel

from lingxi.contract import R
from lingxi.domain.workspace.service import create_workspace, list_workspaces

router = APIRouter()


class CreateWorkspaceRequest(BaseModel):
    """创建工作区请求：目录必填，名称缺省取目录名。"""

    path: str
    name: str = ""


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
