"""会话输入队列接口：运行中排队、编辑与撤回。"""

from fastapi import APIRouter
from pydantic import BaseModel, Field

from lingxi.app.auth import CurrentUserDep
from lingxi.contract import R
from lingxi.domain.queue import service as queue_service
from lingxi.domain.session.service import get_session


class QueueAddRequest(BaseModel):
    """新增排队消息。"""

    content: str = Field(min_length=1)


class QueueUpdateRequest(BaseModel):
    """编辑排队消息。"""

    content: str = Field(min_length=1)


def make_router() -> APIRouter:
    """构建输入队列路由。"""
    router = APIRouter()

    @router.get("/sessions/{session_id}/queue")
    async def list_queue(session_id: int, user: CurrentUserDep) -> dict[str, object]:
        """按写入顺序列出待消费消息。"""
        await get_session(session_id, user.id)
        rows = await queue_service.list_pending(session_id)
        return R.ok(
            {
                "items": [
                    {"id": str(row.id), "content": row.content, "created_at": row.created_at.isoformat()}
                    for row in rows
                ]
            }
        ).to_dict()

    @router.post("/sessions/{session_id}/queue")
    async def add_queue(session_id: int, request: QueueAddRequest, user: CurrentUserDep) -> dict[str, object]:
        """追加一条排队消息。"""
        await get_session(session_id, user.id)
        item_id = await queue_service.enqueue(session_id, request.content)
        return R.ok({"id": str(item_id)}).to_dict()

    @router.patch("/sessions/{session_id}/queue/{item_id}")
    async def update_queue(
        session_id: int, item_id: int, request: QueueUpdateRequest, user: CurrentUserDep
    ) -> dict[str, object]:
        """编辑仍待消费的队列项。"""
        await get_session(session_id, user.id)
        updated = await queue_service.update_item(session_id, item_id, request.content)
        return R.ok({"updated": updated}).to_dict()

    @router.delete("/sessions/{session_id}/queue/{item_id}")
    async def delete_queue(session_id: int, item_id: int, user: CurrentUserDep) -> dict[str, object]:
        """撤回仍待消费的队列项。"""
        await get_session(session_id, user.id)
        removed = await queue_service.cancel_item(session_id, item_id)
        return R.ok({"removed": removed}).to_dict()

    return router
