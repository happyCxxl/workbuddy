"""会话接口：创建、查询与事件重放。"""

from fastapi import APIRouter
from pydantic import BaseModel

from lingxi.contract import R
from lingxi.core.log import read_events
from lingxi.domain.session.service import create_session, get_session

router = APIRouter()


class CreateSessionRequest(BaseModel):
    """建会话请求：工作目录必填。"""

    work_dir: str
    title: str = ""


@router.post("/sessions")
async def create(request: CreateSessionRequest) -> dict[str, object]:
    """建会话：绑定当前人格版本（ID 一律字符串）。"""
    session_id = await create_session(request.work_dir, request.title)
    return R.ok({"id": str(session_id)}).to_dict()


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
            "persona_version_id": str(row.persona_version_id) if row.persona_version_id else None,
        }
    ).to_dict()


@router.get("/sessions/{session_id}/events")
async def events(session_id: int, after_seq: int = 0, limit: int = 500) -> dict[str, object]:
    """事件重放：after_seq 之后的事件（界面重建 = after_seq 0；断线补齐 = 客户端游标）。"""
    rows = await read_events(session_id, after_seq, limit)
    return R.ok({"events": [{"seq": e.seq, "type": e.type, "payload": e.payload} for e in rows]}).to_dict()
