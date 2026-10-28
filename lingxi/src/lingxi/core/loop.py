"""单 ReAct 循环：一条用户消息跑一个回合；事件日志全程唯一事实源。"""

import asyncio
import logging
from collections.abc import AsyncIterator
from typing import cast

from lingxi.app.config import Settings
from lingxi.contract import ApiError
from lingxi.core.events import TurnEvent
from lingxi.core.hooks import HookRegistry, TurnContext, make_budget_gate, make_max_steps_gate
from lingxi.core.log import EventType, append_event
from lingxi.domain.llm.gateway import LlmGateway
from lingxi.domain.memory.embedding import Embedder
from lingxi.domain.memory.service import recall
from lingxi.domain.persona.engine import render_system_prompt
from lingxi.domain.persona.models import Persona
from lingxi.domain.ports import ChatMessage, ChatRequest, ToolCall
from lingxi.domain.session.service import build_history, compact_history, get_session, persona_of_session

logger = logging.getLogger(__name__)


def _estimate_tokens(messages: list[ChatMessage]) -> int:
    """粗略估算输入 token：每 4 字符记 1 token。"""
    return sum(len(m.content) for m in messages) // 4


class TurnRunner:
    """回合执行器：同会话一次一个回合（串行锁）；支持停车申请（v1 只停止不回退）。"""

    def __init__(self, settings: Settings, gateway: LlmGateway, hooks: HookRegistry, embedder: Embedder) -> None:
        self._settings = settings
        self._gateway = gateway
        self._hooks = hooks
        self._embedder = embedder
        self._locks: dict[int, asyncio.Lock] = {}
        self._stop_requested: set[int] = set()
        self._hooks.register(
            "pre_step", make_budget_gate(settings.loop.input_token_budget, settings.loop.context_budget_ratio)
        )
        self._hooks.register("pre_step", make_max_steps_gate(settings.loop.max_steps))

    def request_stop(self, session_id: int) -> None:
        """申请停车：当前回合在下一步前停止，状态记入 turn/end 事件。"""
        self._stop_requested.add(session_id)

    async def run(self, session_id: int, user_content: str) -> AsyncIterator[TurnEvent]:
        """跑一个回合：产出与日志同构的事件流；异常路径补齐 error 与 turn/end 后上抛。"""
        lock = self._locks.setdefault(session_id, asyncio.Lock())
        async with lock:
            async for event in self._run_locked(session_id, user_content):
                yield event

    async def _run_locked(self, session_id: int, user_content: str) -> AsyncIterator[TurnEvent]:
        session = await get_session(session_id)
        persona = await persona_of_session(session)
        try:
            pre_turn = TurnContext(
                session_id=session_id, turn_seq=0, step=0, action="pre_turn", payload={"input": user_content}
            )
            await self._hooks.run("pre_turn", pre_turn)
            start_event = await self._emit(session_id, EventType.TURN_START, {"work_dir": session.work_dir})
            yield start_event
            yield await self._emit(session_id, EventType.USER_MESSAGE, {"content": user_content})
            turn_seq = cast(int, start_event.payload["seq"])
            async for event in self._steps(session_id, turn_seq, persona, session.work_dir, user_content):
                yield event
            self._stop_requested.discard(session_id)
        except ApiError as exc:
            yield await self._emit(session_id, EventType.ERROR, {"code": exc.error.code, "message": str(exc)})
            yield await self._emit(session_id, EventType.TURN_END, {"status": "aborted"})
            raise
        except Exception as exc:
            logger.exception("回合异常")
            yield await self._emit(session_id, EventType.ERROR, {"message": str(exc)})
            yield await self._emit(session_id, EventType.TURN_END, {"status": "error"})
            raise

    async def _steps(
        self, session_id: int, turn_seq: int, persona: Persona, work_dir: str, user_content: str
    ) -> AsyncIterator[TurnEvent]:
        step = 0
        while True:
            step += 1
            messages = await self._assemble(session_id, persona, work_dir, user_content)
            ctx = TurnContext(
                session_id=session_id,
                turn_seq=turn_seq,
                step=step,
                action="pre_step",
                payload={"estimate": _estimate_tokens(messages)},
            )
            await self._hooks.run("pre_step", ctx)  # fail-closed：闸门异常在此上抛
            if ctx.payload.get("compact"):
                await compact_history(session_id)
                messages = await self._assemble(session_id, persona, work_dir, user_content)
            if session_id in self._stop_requested:
                yield await self._emit(session_id, EventType.TURN_END, {"status": "pending_stop"})
                return
            tool_calls: list[ToolCall] = []
            content_parts: list[str] = []
            reasoning_parts: list[str] = []
            async for chunk in self._gateway.stream(ChatRequest(messages=messages)):
                if chunk.kind == "text":
                    content_parts.append(chunk.text)
                    yield await self._emit(session_id, EventType.TEXT_DELTA, {"text": chunk.text})
                elif chunk.kind == "thinking":
                    reasoning_parts.append(chunk.text)
                    yield await self._emit(session_id, EventType.THINKING_DELTA, {"text": chunk.text})
                elif chunk.kind == "tool_calls":
                    tool_calls.extend(chunk.tool_calls)
            if tool_calls:
                for call in tool_calls:
                    async for event in self._execute_tool(session_id, turn_seq, step, call.name, call.arguments):
                        yield event
                continue
            yield await self._emit(
                session_id,
                EventType.ASSISTANT_MESSAGE,
                {"content": "".join(content_parts), "reasoning_content": "".join(reasoning_parts) or None},
            )
            post = TurnContext(
                session_id=session_id,
                turn_seq=turn_seq,
                step=step,
                action="post_execute",
                payload={
                    "work_dir": work_dir,
                    "user_content": user_content,
                    "assistant_content": "".join(content_parts),
                },
            )
            await self._hooks.run("post_execute", post)
            yield await self._emit(session_id, EventType.TURN_END, {"status": "completed"})
            return

    async def _execute_tool(
        self, session_id: int, turn_seq: int, step: int, name: str, arguments: str
    ) -> AsyncIterator[TurnEvent]:
        """执行一步工具：pre-execute 钩子 fail-closed；S8 为工具壳，真执行自 S12 起接入。"""
        ctx = TurnContext(
            session_id=session_id,
            turn_seq=turn_seq,
            step=step,
            action="pre_execute",
            tool_name=name,
            payload={"arguments": arguments},
        )
        await self._hooks.run("pre_execute", ctx)
        yield await self._emit(session_id, EventType.TOOL_CALL, {"name": name, "arguments": arguments})
        yield await self._emit(session_id, EventType.TOOL_RESULT, {"name": name, "error": "工具未接入"})
        yield await self._emit(session_id, EventType.USER_MESSAGE, {"content": f"工具 {name} 返回：未接入"})

    async def _assemble(self, session_id: int, persona: Persona, work_dir: str, query: str) -> list[ChatMessage]:
        """组装模型输入：人格系统提示 + 记忆资料段（带编号与来源时间）+ 历史投影。"""
        messages = [ChatMessage(role="system", content=render_system_prompt(persona))]
        memories = await recall(work_dir, query, self._embedder)
        if memories:
            lines = ["记忆资料："]
            for index, memory in enumerate(memories, start=1):
                lines.append(f"{index}. （{memory.kind}，{memory.created_at:%Y-%m-%d}）{memory.content}")
            messages.append(ChatMessage(role="system", content="\n".join(lines)))
        messages.extend(await build_history(session_id))
        return messages

    @staticmethod
    async def _emit(session_id: int, event_type: EventType, payload: dict[str, object]) -> TurnEvent:
        """落日志并产出同构回合事件：日志是唯一事实源，产出流是其投影。"""
        seq = await append_event(session_id, event_type, payload)
        payload["seq"] = seq
        return TurnEvent(type=str(event_type), payload=payload)
