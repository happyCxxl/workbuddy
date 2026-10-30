"""会话接口：创建（默认权限档）、查询、权限档切换、事件重放与后台任务。"""

from fastapi import APIRouter
from pydantic import BaseModel

from lingxi.app.config import Settings
from lingxi.contract import ApiError, ErrorCode, R
from lingxi.core.log import read_events
from lingxi.core.sub_sessions import SubSessionManager
from lingxi.domain.permission.gate import PRESETS
from lingxi.domain.session.service import (
    create_session,
    delete_session,
    get_session,
    list_model_calls,
    list_sessions,
    rename_session,
    session_usage,
    set_session_preset,
)
from lingxi.domain.sub_session.service import create_sub_session, get_sub_session, list_sub_sessions
from lingxi.domain.workspace.service import get_workspace


class CreateSessionRequest(BaseModel):
    """建会话请求：归属工作区（workspace_id）或直接给工作目录；标题缺省由首条消息自动生成。"""

    work_dir: str = ""
    title: str = ""
    workspace_id: str | None = None


class RenameRequest(BaseModel):
    """重命名请求：标题非空。"""

    title: str


class PresetRequest(BaseModel):
    """会话权限档切换请求。"""

    preset: str


class SubSessionRequest(BaseModel):
    """后台任务请求：任务必填，标题可选。"""

    task: str
    title: str = ""


def _serialize_sub(row: object) -> dict[str, object]:
    """后台任务序列化：ID 一律字符串。"""
    from lingxi.db.models import SubSession

    assert isinstance(row, SubSession)
    return {
        "id": str(row.id),
        "parent_session_id": str(row.parent_session_id),
        "child_session_id": str(row.child_session_id),
        "title": row.title,
        "task": row.task,
        "status": row.status,
        "result": row.result,
        "created_at": row.created_at.isoformat(),
    }


def make_router(settings: Settings, manager: SubSessionManager) -> APIRouter:
    """构造会话路由：闭包持有默认权限档配置与后台任务管理器。"""

    router = APIRouter()

    @router.post("/sessions")
    async def create(request: CreateSessionRequest) -> dict[str, object]:
        """建会话：归属工作区（推荐）或直接给目录；绑定当前人格与默认权限档。"""
        workspace_id: int | None = None
        work_dir = request.work_dir.strip()
        if request.workspace_id:
            workspace = await get_workspace(int(request.workspace_id))
            workspace_id = workspace.id
            work_dir = workspace.path
        elif not work_dir:
            raise ApiError(ErrorCode.INVALID_ARGUMENT, "必须提供 workspace_id 或 work_dir")
        session_id = await create_session(
            work_dir, request.title, settings.permission.preset, workspace_id=workspace_id
        )
        return R.ok({"id": str(session_id)}).to_dict()

    @router.get("/sessions")
    async def list_all() -> dict[str, object]:
        """最近会话列表：对话页左栏。"""
        rows = await list_sessions()
        return R.ok(
            {
                "sessions": [
                    {
                        "id": str(row.id),
                        "work_dir": row.work_dir,
                        "title": row.title,
                        "status": row.status,
                        "permission_preset": row.permission_preset,
                        "workspace_id": str(row.workspace_id) if row.workspace_id else None,
                        "persona_version_id": str(row.persona_version_id) if row.persona_version_id else None,
                        "updated_at": row.updated_at.isoformat(),
                    }
                    for row in rows
                ]
            }
        ).to_dict()

    @router.get("/sessions/{session_id}")
    async def get(session_id: int) -> dict[str, object]:
        """读会话。"""
        row = await get_session(session_id)
        return R.ok(
            {
                "id": str(row.id),
                "work_dir": row.work_dir,
                "title": row.title,
                "status": row.status,
                "permission_preset": row.permission_preset,
                "workspace_id": str(row.workspace_id) if row.workspace_id else None,
                "persona_version_id": str(row.persona_version_id) if row.persona_version_id else None,
                "updated_at": row.updated_at.isoformat(),
            }
        ).to_dict()

    @router.put("/sessions/{session_id}/permission")
    async def set_permission(session_id: int, request: PresetRequest) -> dict[str, object]:
        """切换本会话权限档：只影响该会话，落 permission/policy 事件。"""
        if request.preset not in PRESETS:
            raise ApiError(ErrorCode.INVALID_ARGUMENT, f"未知预设档位：{request.preset}")
        await set_session_preset(session_id, request.preset)
        return R.ok({"preset": request.preset}).to_dict()

    @router.get("/sessions/{session_id}/events")
    async def events(session_id: int, after_seq: int = 0, limit: int = 500) -> dict[str, object]:
        """事件重放：after_seq 之后的事件（界面重建 = after_seq 0；断线补齐 = 客户端游标）。"""
        rows = await read_events(session_id, after_seq, limit)
        return R.ok(
            {
                "events": [
                    {"seq": e.seq, "type": e.type, "payload": e.payload, "created_at": e.created_at.isoformat()}
                    for e in rows
                ]
            }
        ).to_dict()

    @router.get("/sessions/{session_id}/calls")
    async def calls(session_id: int) -> dict[str, object]:
        """模型调用明细：回合/步骤、模型、token、耗时与错误（轨迹时间线用）。"""
        rows = await list_model_calls(session_id)
        return R.ok(
            {
                "calls": [
                    {
                        "turn_seq": row.turn_seq,
                        "step_seq": row.step_seq,
                        "model": row.model,
                        "prompt_tokens": row.prompt_tokens,
                        "completion_tokens": row.completion_tokens,
                        "latency_ms": row.latency_ms,
                        "error_code": row.error_code,
                        "created_at": row.created_at.isoformat(),
                    }
                    for row in rows
                ]
            }
        ).to_dict()

    @router.get("/sessions/{session_id}/usage")
    async def usage(session_id: int) -> dict[str, object]:
        """会话用量：调用次数、token 与缓存命中（来自模型调用明细）。"""
        calls, prompt_tokens, completion_tokens, cache_hit, cache_miss = await session_usage(session_id)
        return R.ok(
            {
                "calls": calls,
                "prompt_tokens": prompt_tokens,
                "completion_tokens": completion_tokens,
                "prompt_cache_hit_tokens": cache_hit,
                "prompt_cache_miss_tokens": cache_miss,
            }
        ).to_dict()

    @router.post("/sessions/{session_id}/subsessions")
    async def create_sub(session_id: int, request: SubSessionRequest) -> dict[str, object]:
        """提交后台任务：立即返回，结果通过列表与父会话事件查看。"""
        if not request.task.strip():
            raise ApiError(ErrorCode.INVALID_ARGUMENT, "任务不能为空")
        row = await create_sub_session(session_id, request.title, request.task.strip())
        manager.start(session_id, row.id, row.child_session_id, row.title, row.task)
        return R.ok({"id": str(row.id), "child_session_id": str(row.child_session_id)}).to_dict()

    @router.get("/sessions/{session_id}/subsessions")
    async def list_subs(session_id: int) -> dict[str, object]:
        """父会话的后台任务列表：新建在前。"""
        rows = await list_sub_sessions(session_id)
        return R.ok({"subsessions": [_serialize_sub(row) for row in rows]}).to_dict()

    @router.get("/subsessions/{sub_session_id}")
    async def sub_detail(sub_session_id: int) -> dict[str, object]:
        """单个后台任务：状态与结果摘要。"""
        row = await get_sub_session(sub_session_id)
        return R.ok(_serialize_sub(row)).to_dict()

    @router.patch("/sessions/{session_id}")
    async def rename(session_id: int, request: RenameRequest) -> dict[str, object]:
        """重命名会话（逻辑删除的不可改）。"""
        await rename_session(session_id, request.title)
        return R.ok({"title": request.title.strip()}).to_dict()

    @router.delete("/sessions/{session_id}")
    async def remove(session_id: int) -> dict[str, object]:
        """逻辑删除单条会话：行与事件保留，只从列表消失。"""
        await delete_session(session_id)
        return R.ok(None).to_dict()

    return router
