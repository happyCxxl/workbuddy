"""会话接口：创建（默认权限档）、查询、权限档切换与事件重放。"""

from fastapi import APIRouter
from pydantic import BaseModel

from lingxi.app.config import Settings
from lingxi.contract import ApiError, ErrorCode, R
from lingxi.core.log import read_events
from lingxi.domain.permission.gate import PRESETS
from lingxi.domain.session.service import (
    create_session,
    get_session,
    list_sessions,
    session_usage,
    set_session_preset,
)


class CreateSessionRequest(BaseModel):
    """建会话请求：工作目录必填。"""

    work_dir: str
    title: str = ""


class PresetRequest(BaseModel):
    """会话权限档切换请求。"""

    preset: str


def make_router(settings: Settings) -> APIRouter:
    """构造会话路由：闭包持有默认权限档配置。"""

    router = APIRouter()

    @router.post("/sessions")
    async def create(request: CreateSessionRequest) -> dict[str, object]:
        """建会话：绑定当前启用人格版本与默认权限档（ID 一律字符串）。"""
        session_id = await create_session(request.work_dir, request.title, settings.permission.preset)
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
        return R.ok({"events": [{"seq": e.seq, "type": e.type, "payload": e.payload} for e in rows]}).to_dict()

    @router.get("/sessions/{session_id}/usage")
    async def usage(session_id: int) -> dict[str, object]:
        """会话用量：调用次数与 token 汇总（来自模型调用明细）。"""
        calls, prompt_tokens, completion_tokens = await session_usage(session_id)
        return R.ok({"calls": calls, "prompt_tokens": prompt_tokens, "completion_tokens": completion_tokens}).to_dict()

    return router
