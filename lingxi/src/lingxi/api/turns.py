"""回合接口：SSE 流式下发回合事件与审批恢复，末尾 DONE 哨兵。"""

from collections.abc import AsyncIterator
from typing import Literal, cast

from fastapi import APIRouter
from fastapi.responses import StreamingResponse
from pydantic import BaseModel

from lingxi.contract import ApiError, R
from lingxi.core.events import DONE_FRAME, TurnEvent, sse_frame
from lingxi.core.loop import TurnRunner


class TurnRequest(BaseModel):
    """回合请求。"""

    content: str


class ResumeRequest(BaseModel):
    """审批裁决请求。"""

    request_id: str
    outcome: Literal["allowed-once", "allowed-always", "rejected"]


def _sse(events: AsyncIterator[object]) -> StreamingResponse:
    """事件流 → SSE 响应：末尾 DONE；业务失败由执行器已补 error 帧，此处收尾。"""

    async def gen() -> AsyncIterator[str]:
        try:
            async for event in events:
                yield sse_frame(cast(TurnEvent, event))
        except ApiError:
            # 业务失败已由执行器补 error 与 turn/end 帧；此处只负责让流正常收尾
            pass
        finally:
            yield DONE_FRAME

    return StreamingResponse(gen(), media_type="text/event-stream")


def make_router(runner: TurnRunner) -> APIRouter:
    """构造回合路由：闭包持有回合执行器。"""

    router = APIRouter()

    @router.post("/sessions/{session_id}/turns")
    async def create_turn(session_id: int, request: TurnRequest) -> StreamingResponse:
        """建回合：SSE 下发完整事件流（turn/start → … → turn.end），末尾 DONE。"""
        return _sse(runner.run(session_id, request.content))

    @router.post("/sessions/{session_id}/turns/resume")
    async def resume_turn(session_id: int, request: ResumeRequest) -> StreamingResponse:
        """审批裁决后恢复重放：SSE 续流，末尾 DONE。"""
        return _sse(runner.resume(session_id, request.request_id, request.outcome))

    @router.post("/sessions/{session_id}/turns/stop")
    async def stop_turn(session_id: int) -> dict[str, object]:
        """申请停止：当前回合在下一步前或流式块边界停止（v1 只停止不回退）。"""
        runner.request_stop(session_id)
        return R.ok(None).to_dict()

    return router
