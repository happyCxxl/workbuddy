"""单 ReAct 循环：一条用户消息跑一个回合；事件日志全程唯一事实源。"""

import asyncio
import logging
from collections.abc import AsyncIterator

from lingxi.app.config import Settings
from lingxi.contract import ApiError
from lingxi.core.hooks import HookRegistry, TurnContext, make_budget_gate, make_max_steps_gate
from lingxi.core.log import EventType, append_event
from lingxi.domain.llm.gateway import LlmGateway
from lingxi.domain.persona.engine import render_system_prompt
from lingxi.domain.persona.models import Persona
from lingxi.domain.ports import ChatMessage, ChatRequest, StreamChunk, ToolCall
from lingxi.domain.session.service import build_history, compact_history, get_session, persona_of_session

logger = logging.getLogger(__name__)


def _estimate_tokens(messages: list[ChatMessage]) -> int:
    """粗略估算输入 token：每 4 字符记 1 token。"""
    return sum(len(m.content) for m in messages) // 4


class TurnRunner:
    """回合执行器：同会话一次一个回合（串行锁）；支持停车申请（v1 只停止不回退）。"""

    def __init__(self, settings: Settings, gateway: LlmGateway, hooks: HookRegistry) -> None:
        self._settings = settings
        self._gateway = gateway
        self._hooks = hooks
        self._locks: dict[int, asyncio.Lock] = {}
        self._stop_requested: set[int] = set()
        self._hooks.register(
            "pre_step", make_budget_gate(settings.loop.input_token_budget, settings.loop.context_budget_ratio)
        )
        self._hooks.register("pre_step", make_max_steps_gate(settings.loop.max_steps))

    def request_stop(self, session_id: int) -> None:
        """申请停车：当前回合在下一步前停止，状态记入 turn/end 事件。"""
        self._stop_requested.add(session_id)

    async def run(self, session_id: int, user_content: str) -> AsyncIterator[StreamChunk]:
        """跑一个回合：产出归一化增量流；异常路径补齐 error 与 turn/end 事件后上抛。"""
        lock = self._locks.setdefault(session_id, asyncio.Lock())
        async with lock:
            async for chunk in self._run_locked(session_id, user_content):
                yield chunk

    async def _run_locked(self, session_id: int, user_content: str) -> AsyncIterator[StreamChunk]:
        session = await get_session(session_id)
        persona = await persona_of_session(session)
        try:
            pre_turn = TurnContext(
                session_id=session_id, turn_seq=0, step=0, action="pre_turn", payload={"input": user_content}
            )
            await self._hooks.run("pre_turn", pre_turn)
            turn_seq = await append_event(session_id, EventType.TURN_START, {"work_dir": session.work_dir})
            await append_event(session_id, EventType.USER_MESSAGE, {"content": user_content})
            async for chunk in self._steps(session_id, turn_seq, persona):
                yield chunk
            self._stop_requested.discard(session_id)
        except ApiError as exc:
            await append_event(session_id, EventType.ERROR, {"code": exc.error.code, "message": str(exc)})
            await append_event(session_id, EventType.TURN_END, {"status": "aborted"})
            raise
        except Exception as exc:
            logger.exception("回合异常")
            await append_event(session_id, EventType.ERROR, {"message": str(exc)})
            await append_event(session_id, EventType.TURN_END, {"status": "error"})
            raise

    async def _steps(self, session_id: int, turn_seq: int, persona: Persona) -> AsyncIterator[StreamChunk]:
        step = 0
        while True:
            step += 1
            messages = await self._assemble(session_id, persona)
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
                messages = await self._assemble(session_id, persona)
            if session_id in self._stop_requested:
                await append_event(session_id, EventType.TURN_END, {"status": "pending_stop"})
                return
            tool_calls: list[ToolCall] = []
            content_parts: list[str] = []
            reasoning_parts: list[str] = []
            async for chunk in self._gateway.stream(ChatRequest(messages=messages)):
                yield chunk
                if chunk.kind == "text":
                    content_parts.append(chunk.text)
                    await append_event(session_id, EventType.TEXT_DELTA, {"text": chunk.text})
                elif chunk.kind == "thinking":
                    reasoning_parts.append(chunk.text)
                    await append_event(session_id, EventType.THINKING_DELTA, {"text": chunk.text})
                elif chunk.kind == "tool_calls":
                    tool_calls.extend(chunk.tool_calls)
            if tool_calls:
                for call in tool_calls:
                    await self._execute_tool(session_id, turn_seq, step, call.name, call.arguments)
                continue
            await append_event(
                session_id,
                EventType.ASSISTANT_MESSAGE,
                {"content": "".join(content_parts), "reasoning_content": "".join(reasoning_parts) or None},
            )
            post = TurnContext(session_id=session_id, turn_seq=turn_seq, step=step, action="post_execute")
            await self._hooks.run("post_execute", post)
            await append_event(session_id, EventType.TURN_END, {"status": "completed"})
            return

    async def _execute_tool(self, session_id: int, turn_seq: int, step: int, name: str, arguments: str) -> None:
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
        await append_event(session_id, EventType.TOOL_CALL, {"name": name, "arguments": arguments})
        await append_event(session_id, EventType.TOOL_RESULT, {"name": name, "error": "工具未接入"})
        await append_event(session_id, EventType.USER_MESSAGE, {"content": f"工具 {name} 返回：未接入"})

    async def _assemble(self, session_id: int, persona: Persona) -> list[ChatMessage]:
        """组装模型输入：人格系统提示 + 历史投影；记忆 top-8 自 S11 接入。"""
        messages = [ChatMessage(role="system", content=render_system_prompt(persona))]
        messages.extend(await build_history(session_id))
        return messages
