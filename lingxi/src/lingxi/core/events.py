"""SSE 事件流：回合事件的帧序列化；事件类型取值于 EventType 登记表。"""

import json

from pydantic import BaseModel

DONE_FRAME = "data: [DONE]\n\n"


class TurnEvent(BaseModel):
    """回合事件：类型取值于 EventType 登记表，载荷与落库事件同构。"""

    type: str
    payload: dict[str, object]


def sse_frame(event: TurnEvent) -> str:
    """序列化为 SSE 帧：event 行 + data 行（Anthropic 式命名事件）。"""
    return f"event: {event.type}\ndata: {json.dumps(event.payload, ensure_ascii=False)}\n\n"
