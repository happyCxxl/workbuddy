"""后台任务调度：后台跑一轮 ReAct（消费事件落日志），完成后在父会话记 subsession 事件。"""

import asyncio
import logging

from lingxi.contract import ApiError
from lingxi.core.events import TurnEvent
from lingxi.core.log import EventType, append_event
from lingxi.core.loop import TurnRunner
from lingxi.domain.sub_session.service import finish_sub_session

logger = logging.getLogger(__name__)

SUMMARY_CHARS = 500


class SubSessionManager:
    """后台任务管理器：持有回合执行器，任务协程独立于请求生命周期。"""

    def __init__(self, runner: TurnRunner) -> None:
        self._runner = runner
        self._tasks: set[asyncio.Task[None]] = set()

    def start(self, parent_session_id: int, sub_session_id: int, child_session_id: int, title: str, task: str) -> None:
        """提交后台任务：立即返回，执行结果通过父会话事件与 sub_session 状态呈现。"""
        task_handle = asyncio.create_task(self._run(parent_session_id, sub_session_id, child_session_id, title, task))
        self._tasks.add(task_handle)
        task_handle.add_done_callback(self._tasks.discard)

    async def _run(
        self, parent_session_id: int, sub_session_id: int, child_session_id: int, title: str, task: str
    ) -> None:
        """执行任务：成功/失败都落状态与父会话事件（事件是唯一事实源）。"""
        await append_event(
            parent_session_id,
            EventType.SUBSESSION_STARTED,
            {"sub_session_id": str(sub_session_id), "child_session_id": str(child_session_id), "title": title},
        )
        status = "completed"
        summary = ""
        try:
            async for event in self._runner.run(child_session_id, task):
                summary = self._collect(event, summary)
        except ApiError as exc:
            status = "failed"
            summary = str(exc)
        except Exception as exc:  # noqa: BLE001 —— 后台任务兜底：任何异常都要落状态，不能悬挂 running
            logger.exception("后台任务执行异常：sub=%s", sub_session_id)
            status = "failed"
            summary = str(exc)
        text = (summary or "（无输出）")[:SUMMARY_CHARS]
        # 事件先落（事实源），状态后写：轮询到终态时父会话事件一定已可见
        event_type = EventType.SUBSESSION_COMPLETED if status == "completed" else EventType.SUBSESSION_FAILED
        await append_event(
            parent_session_id,
            event_type,
            {"sub_session_id": str(sub_session_id), "status": status, "summary": text},
        )
        await finish_sub_session(sub_session_id, status, text)

    @staticmethod
    def _collect(event: TurnEvent, summary: str) -> str:
        """收集结果摘要：最后一条助手消息优先，错误信息次之。"""
        if event.type == EventType.ASSISTANT_MESSAGE.value:
            content = str(event.payload.get("content") or "").strip()
            if content:
                return content
        if event.type == EventType.ERROR.value:
            return str(event.payload.get("message") or "")
        return summary
