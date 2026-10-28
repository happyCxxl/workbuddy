"""回合接口：SSE 流式下发回合事件，末尾 DONE 哨兵。"""

from collections.abc import AsyncIterator

from fastapi import APIRouter
from fastapi.responses import StreamingResponse
from pydantic import BaseModel

from lingxi.contract import ApiError
from lingxi.core.events import DONE_FRAME, sse_frame
from lingxi.core.loop import TurnRunner


class TurnRequest(BaseModel):
    """回合请求。"""

    content: str


def make_router(runner: TurnRunner) -> APIRouter:
    """构造回合路由：闭包持有回合执行器。"""

    router = APIRouter()

    @router.post("/sessions/{session_id}/turns")
    async def create_turn(session_id: int, request: TurnRequest) -> StreamingResponse:
        """建回合：SSE 下发完整事件流（turn/start → … → turn.end），末尾 DONE。"""

        async def gen() -> AsyncIterator[str]:
            try:
                async for event in runner.run(session_id, request.content):
                    yield sse_frame(event)
            except ApiError:
                # 回合执行器已把 error 与 turn/end 作为帧下发并落日志；此处只负责让流正常收尾
                pass
            finally:
                yield DONE_FRAME

        return StreamingResponse(gen(), media_type="text/event-stream")

    return router
