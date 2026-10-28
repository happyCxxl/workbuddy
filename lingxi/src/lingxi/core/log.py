"""事件日志：唯一事实源——会话事件按序追加，历史、界面与轨迹全部由它派生。"""

from enum import StrEnum

from sqlalchemy.exc import IntegrityError

from lingxi.db.engine import session_scope
from lingxi.db.models import SessionEvent
from lingxi.db.repo.event_repo import EventRepo


class EventType(StrEnum):
    """事件类型：主文档 §9.2 的十一类，新增类型先在此登记。"""

    TEXT_DELTA = "text.delta"
    THINKING_DELTA = "thinking.delta"
    STEP_START = "step.start"
    STEP_END = "step.end"
    TOOL_CALL = "tool.call"
    TOOL_RESULT = "tool.result"
    PERMISSION_REQUIRED = "permission.required"
    COMPACTION = "compaction"
    SUBSESSION = "subsession"
    TURN_END = "turn.end"
    ERROR = "error"


async def append_event(session_id: int, event_type: EventType, payload: dict[str, object]) -> int:
    """追加事件：seq = 会话内最大 seq + 1；唯一键冲突且同 seq 已有记录时幂等返回其 seq。"""
    async with session_scope() as s:
        repo = EventRepo(s)
        seq = await repo.max_seq(session_id) + 1
        event = SessionEvent(session_id=session_id, seq=seq, type=str(event_type), payload=payload)
        try:
            await repo.insert(event)
            await s.commit()
            return seq
        except IntegrityError:
            await s.rollback()
            existing = await repo.get(session_id, seq)
            if existing is not None:
                return existing.seq
            raise


async def read_events(session_id: int, after_seq: int = 0, limit: int = 500) -> list[SessionEvent]:
    """按 seq 读：after_seq 之后（不含）最多 limit 条。"""
    async with session_scope() as s:
        return await EventRepo(s).list_after(session_id, after_seq, limit)


async def replay_events(session_id: int, after_seq: int = 0) -> list[SessionEvent]:
    """重放：after_seq 之后全部事件（界面重建与断线恢复用）。"""
    async with session_scope() as s:
        return await EventRepo(s).list_after(session_id, after_seq, None)
