"""检查点接口：列出回合检查点，并按模式回退操作。"""

from typing import Literal

from fastapi import APIRouter
from pydantic import BaseModel

from lingxi.app.auth import CurrentUserDep
from lingxi.contract import R
from lingxi.domain.checkpoint import service
from lingxi.domain.session.service import get_session


class RewindRequest(BaseModel):
    """回退模式：code / conversation / both。"""

    mode: Literal["code", "conversation", "both"] = "both"


def make_router() -> APIRouter:
    """构建检查点路由。"""
    router = APIRouter()

    @router.get("/sessions/{session_id}/checkpoints")
    async def list_checkpoints(session_id: int, user: CurrentUserDep) -> dict[str, object]:
        """会话检查点：新在前，附带每个检查点的文件改动数。"""
        await get_session(session_id, user.id)
        rows = await service.list_checkpoints(session_id)
        return R.ok(
            {
                "checkpoints": [
                    {
                        "id": str(row.id),
                        "turn_seq": row.turn_seq,
                        "prompt": row.prompt,
                        "file_count": len(row.files),
                        "created_at": row.created_at.isoformat(),
                    }
                    for row in rows
                ]
            }
        ).to_dict()

    @router.post("/sessions/{session_id}/checkpoints/{checkpoint_id}/rewind")
    async def rewind(
        session_id: int, checkpoint_id: int, request: RewindRequest, user: CurrentUserDep
    ) -> dict[str, object]:
        """回退到检查点：恢复文件、对话或两者，并返回原始 prompt。"""
        await get_session(session_id, user.id)
        result = await service.rewind(session_id, checkpoint_id, request.mode)
        return R.ok(result).to_dict()

    return router
