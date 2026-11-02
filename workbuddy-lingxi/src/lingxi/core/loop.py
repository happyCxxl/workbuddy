"""单 ReAct 循环：一条用户消息跑一个回合；事件日志全程唯一事实源。"""

import asyncio
import json
import logging
import os
import platform
import sys
from collections.abc import AsyncIterator
from dataclasses import dataclass, field
from datetime import datetime
from typing import cast
from uuid import uuid4

from lingxi.app.config import Settings
from lingxi.contract import SESSION_ID, ApiError, ErrorCode
from lingxi.core.events import TurnEvent
from lingxi.core.hooks import HookRegistry, TurnContext, make_budget_gate, make_max_steps_gate
from lingxi.core.log import EventType, append_event
from lingxi.core.prompts import load_prompt, render_prompt
from lingxi.domain.checkpoint.service import create_checkpoint, snapshot_file
from lingxi.domain.llm.gateway import LlmGateway
from lingxi.domain.memory.embedding import Embedder
from lingxi.domain.memory.service import recall
from lingxi.domain.permission.gate import PendingApproval, PermissionGate
from lingxi.domain.persona.engine import render_system_prompt
from lingxi.domain.persona.models import Persona
from lingxi.domain.ports import ChatMessage, ChatRequest, ToolCall
from lingxi.domain.queue.coordinator import coordinator as queue_coordinator
from lingxi.domain.session.service import (
    build_history,
    compact_history,
    ensure_title,
    get_session,
    persona_of_session,
)
from lingxi.domain.skills.service import find_skill
from lingxi.domain.tools.registry import SessionToolState, ToolContext, ToolRegistry

logger = logging.getLogger(__name__)


@dataclass
class TurnControl:
    """一次回合的取消信号：只命中创建它的那一轮，不复用、不跨轮。"""

    event: asyncio.Event = field(default_factory=asyncio.Event)
    reason: str = "user"

    @property
    def cancelled(self) -> bool:
        """是否已请求取消。"""
        return self.event.is_set()


def _estimate_tokens(messages: list[ChatMessage]) -> int:
    """粗略估算输入 token：每 4 字符记 1 token。"""
    return sum(len(m.content) for m in messages) // 4


@dataclass
class PendingBatch:
    """审批挂起批次：保存完整 tool_calls、当前下标与调用事件状态。"""

    turn_seq: int
    step: int
    calls: list[ToolCall]
    next_index: int
    work_dir: str
    user_content: str
    call_emitted: bool = True


class TurnRunner:
    """回合执行器：同会话一次一个回合（串行锁）；支持停车申请与审批恢复（v1 只停止不回退）。"""

    def __init__(
        self,
        settings: Settings,
        gateway: LlmGateway,
        hooks: HookRegistry,
        embedder: Embedder,
        tools: ToolRegistry,
        permission: PermissionGate,
    ) -> None:
        self._settings = settings
        self._gateway = gateway
        self._hooks = hooks
        self._embedder = embedder
        self._tools = tools
        self._permission = permission
        self._locks: dict[int, asyncio.Lock] = {}
        self._active_turns: dict[int, TurnControl] = {}
        self._pending: dict[tuple[int, str], PendingBatch] = {}
        # 会话级工具状态：已读文件集合（读前写/改门禁）
        self._tool_states: dict[int, SessionToolState] = {}
        self._hooks.register(
            "pre_step", make_budget_gate(settings.loop.input_token_budget, settings.loop.context_budget_ratio)
        )
        self._hooks.register("pre_step", make_max_steps_gate(settings.loop.max_steps))

    def request_stop(self, session_id: int) -> bool:
        """申请停止当前活动回合；审批挂起由 cancel_pending 处理。"""
        control = self._active_turns.get(session_id)
        if control is None:
            return False
        control.reason = "user"
        control.event.set()
        return True

    async def cancel_pending(self, session_id: int) -> bool:
        """停止审批挂起：为未执行调用补显式结果并结束回合。"""
        keys = [key for key in self._pending if key[0] == session_id]
        if not keys:
            return False
        for key in keys:
            batch = self._pending.pop(key, None)
            if batch is None:
                continue
            for call in batch.calls[batch.next_index :]:
                await self._emit(
                    session_id,
                    EventType.TOOL_RESULT,
                    {"name": call.name, "error": "审批未完成，调用已取消", "tool_call_id": call.id},
                )
                await self._emit(
                    session_id,
                    EventType.TOOL_MESSAGE,
                    {
                        "tool_call_id": call.id,
                        "name": call.name,
                        "content": f"工具 {call.name} 未执行：审批未完成",
                    },
                )
            await self._emit(session_id, EventType.TURN_END, {"status": "interrupted", "reason": "user"})
        return True

    async def run(
        self,
        session_id: int,
        user_content: str,
        retry_of: int | None = None,
        skill: str | None = None,
        queue_id: int | None = None,
    ) -> AsyncIterator[TurnEvent]:
        """跑一个回合：产出与日志同构的事件流；异常路径补齐 error 与 turn/end 后上抛。"""
        lock = self._locks.setdefault(session_id, asyncio.Lock())
        async with lock:
            control = TurnControl()
            self._active_turns[session_id] = control
            try:
                async for event in self._run_locked(session_id, user_content, retry_of, control, skill, queue_id):
                    yield event
            finally:
                if self._active_turns.get(session_id) is control:
                    self._active_turns.pop(session_id, None)

    async def resume(self, session_id: int, request_id: str, outcome: str) -> AsyncIterator[TurnEvent]:
        """审批裁决后恢复重放：allowed-once 执行该工具，rejected 记拒绝结果，然后继续循环。"""
        lock = self._locks.setdefault(session_id, asyncio.Lock())
        async with lock:
            control = TurnControl()
            self._active_turns[session_id] = control
            try:
                async for event in self._resume_locked(session_id, request_id, outcome, control):
                    yield event
            finally:
                if self._active_turns.get(session_id) is control:
                    self._active_turns.pop(session_id, None)

    async def _run_locked(
        self,
        session_id: int,
        user_content: str,
        retry_of: int | None = None,
        control: TurnControl | None = None,
        skill: str | None = None,
        queue_id: int | None = None,
    ) -> AsyncIterator[TurnEvent]:
        control = control or TurnControl()
        session = await get_session(session_id)
        if queue_id is not None:
            queued = await queue_coordinator.claim_item(session_id, queue_id)
            if queued is None:
                raise ApiError(ErrorCode.NOT_FOUND, "排队消息已被消费或不存在")
            user_content = queued.content
        skill_text: str | None = None
        if skill:
            found = find_skill(session.work_dir, skill, session_id)
            if found is None:
                raise ApiError(ErrorCode.NOT_FOUND, f"技能不存在：{skill}")
            skill_text = f'<skill name="{found.name}">\n{found.body}\n</skill>'
        # 子会话是任务执行器：不绑人格、不注入记忆；对话会话照旧
        persona = await persona_of_session(session) if session.kind == "chat" else None
        if session.kind == "chat":
            await ensure_title(session_id, user_content)
        SESSION_ID.set(session_id)
        try:
            pre_turn = TurnContext(
                session_id=session_id, turn_seq=0, step=0, action="pre_turn", payload={"input": user_content}
            )
            await self._hooks.run("pre_turn", pre_turn)
            start_event = await self._emit(
                session_id, EventType.TURN_START, {"work_dir": session.work_dir, "retry_of": retry_of}
            )
            yield start_event
            yield await self._emit(session_id, EventType.USER_MESSAGE, {"content": user_content})
            turn_seq = cast(int, start_event.payload["seq"])
            checkpoint_id: int | None = None
            try:
                checkpoint_id = await create_checkpoint(session_id, turn_seq, user_content)
            except Exception:  # noqa: BLE001 检查点失败不阻断主流程
                logger.exception("创建检查点失败")
            async for event in self._steps(
                session_id,
                session.user_id,
                session.model_id,
                turn_seq,
                persona,
                session.work_dir,
                user_content,
                0,
                session.permission_preset,
                session.kind,
                control,
                checkpoint_id,
                skill_text,
            ):
                yield event
        except PendingApproval as exc:
            yield await self._emit(
                session_id,
                EventType.TURN_PAUSED,
                {"request_id": exc.request_id, "status": "approval"},
            )
        except ApiError as exc:
            yield await self._emit(session_id, EventType.ERROR, {"code": exc.error.code, "message": str(exc)})
            yield await self._emit(session_id, EventType.TURN_END, {"status": "aborted"})
            raise
        except Exception as exc:
            logger.exception("回合异常")
            yield await self._emit(session_id, EventType.ERROR, {"message": str(exc)})
            yield await self._emit(session_id, EventType.TURN_END, {"status": "error"})
            raise

    async def _resume_locked(
        self, session_id: int, request_id: str, outcome: str, control: TurnControl | None = None
    ) -> AsyncIterator[TurnEvent]:
        control = control or TurnControl()
        batch = self._pending.pop((session_id, request_id), None)
        if batch is None:
            raise ApiError(ErrorCode.NOT_FOUND, f"审批请求不存在或已裁决：{request_id}")
        SESSION_ID.set(session_id)
        try:
            yield await self._emit(
                session_id, EventType.PERMISSION_DECIDED, {"request_id": request_id, "outcome": outcome}
            )
            session = await get_session(session_id)
            persona = await persona_of_session(session) if session.kind == "chat" else None
            current = batch.calls[batch.next_index]
            async for event in self._execute_tool(
                session_id,
                batch.turn_seq,
                batch.step,
                current,
                batch.work_dir,
                batch.user_content,
                session.permission_preset,
                session.kind,
                control,
                calls=batch.calls,
                call_index=batch.next_index,
                emit_call=False,
                approved_outcome=outcome,
            ):
                yield event
            for index in range(batch.next_index + 1, len(batch.calls)):
                async for event in self._execute_tool(
                    session_id,
                    batch.turn_seq,
                    batch.step,
                    batch.calls[index],
                    batch.work_dir,
                    batch.user_content,
                    session.permission_preset,
                    session.kind,
                    control,
                    calls=batch.calls,
                    call_index=index,
                    emit_call=True,
                ):
                    yield event
            async for event in self._steps(
                session_id,
                session.user_id,
                session.model_id,
                batch.turn_seq,
                persona,
                batch.work_dir,
                batch.user_content,
                batch.step,
                session.permission_preset,
                session.kind,
                control,
            ):
                yield event
        except PendingApproval as exc:
            yield await self._emit(
                session_id,
                EventType.TURN_PAUSED,
                {"request_id": exc.request_id, "status": "approval"},
            )
        except ApiError as exc:
            yield await self._emit(session_id, EventType.ERROR, {"code": exc.error.code, "message": str(exc)})
            yield await self._emit(session_id, EventType.TURN_END, {"status": "aborted"})
            raise
        except Exception as exc:
            logger.exception("回合恢复异常")
            yield await self._emit(session_id, EventType.ERROR, {"message": str(exc)})
            yield await self._emit(session_id, EventType.TURN_END, {"status": "error"})
            raise

    async def _steps(
        self,
        session_id: int,
        user_id: int,
        model_id: int | None,
        turn_seq: int,
        persona: Persona | None,
        work_dir: str,
        user_content: str,
        step_start: int,
        preset: str,
        kind: str,
        control: TurnControl,
        checkpoint_id: int | None = None,
        skill_text: str | None = None,
    ) -> AsyncIterator[TurnEvent]:
        step = step_start
        while True:
            step += 1
            messages = await self._assemble(
                session_id,
                user_id,
                persona,
                work_dir,
                user_content,
                turn_seq,
                first=step == 1,
                skill_text=skill_text,
            )
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
                messages = await self._assemble(
                    session_id,
                    user_id,
                    persona,
                    work_dir,
                    user_content,
                    turn_seq,
                    first=step == 1,
                    skill_text=skill_text,
                )
            if control.cancelled:
                yield await self._emit(
                    session_id, EventType.TURN_END, {"status": "interrupted", "reason": control.reason}
                )
                return
            tool_calls: list[ToolCall] = []
            content_parts: list[str] = []
            reasoning_parts: list[str] = []
            usage = None
            model_name = ""
            request = ChatRequest(messages=messages, tools=self._tools.specs(), model_id=model_id)
            async for chunk in self._gateway.stream(request):
                if control.cancelled:
                    break
                if chunk.kind == "text":
                    content_parts.append(chunk.text)
                    yield await self._emit(session_id, EventType.TEXT_DELTA, {"text": chunk.text})
                elif chunk.kind == "thinking":
                    reasoning_parts.append(chunk.text)
                    yield await self._emit(session_id, EventType.THINKING_DELTA, {"text": chunk.text})
                elif chunk.kind == "tool_calls":
                    tool_calls.extend(chunk.tool_calls)
                elif chunk.kind == "usage":
                    usage = chunk.usage
                    model_name = chunk.model
            usage_data = usage.model_dump() if usage is not None else None
            if control.cancelled:
                content = "".join(content_parts)
                reasoning = "".join(reasoning_parts)
                if content or reasoning:
                    yield await self._emit(
                        session_id,
                        EventType.ASSISTANT_MESSAGE,
                        {
                            "content": content,
                            "reasoning_content": reasoning or None,
                            "interrupted": True,
                            "usage": usage_data,
                            "model": model_name or None,
                        },
                    )
                yield await self._emit(
                    session_id, EventType.TURN_END, {"status": "interrupted", "reason": control.reason}
                )
                return
            if tool_calls:
                yield await self._emit(
                    session_id,
                    EventType.ASSISTANT_MESSAGE,
                    {
                        "content": "".join(content_parts),
                        "reasoning_content": "".join(reasoning_parts) or None,
                        "tool_calls": [{"id": c.id, "name": c.name, "arguments": c.arguments} for c in tool_calls],
                        "usage": usage_data,
                        "model": model_name or None,
                    },
                )
                for index, call in enumerate(tool_calls):
                    if control.cancelled:
                        async for event in self._skip_calls(session_id, tool_calls[index:]):
                            yield event
                        break
                    async for event in self._execute_tool(
                        session_id,
                        turn_seq,
                        step,
                        call,
                        work_dir,
                        user_content,
                        preset,
                        kind,
                        control,
                        checkpoint_id,
                        calls=tool_calls,
                        call_index=index,
                    ):
                        yield event
                if control.cancelled:
                    yield await self._emit(
                        session_id, EventType.TURN_END, {"status": "interrupted", "reason": control.reason}
                    )
                    return
                async for event in self._inject_queue(session_id):
                    yield event
                continue
            yield await self._emit(
                session_id,
                EventType.ASSISTANT_MESSAGE,
                {
                    "content": "".join(content_parts),
                    "reasoning_content": "".join(reasoning_parts) or None,
                    "usage": usage_data,
                },
            )
            post = TurnContext(
                session_id=session_id,
                turn_seq=turn_seq,
                step=step,
                action="post_execute",
                payload={
                    "user_id": user_id,
                    "work_dir": work_dir,
                    "user_content": user_content,
                    "assistant_content": "".join(content_parts),
                },
            )
            # 先落 turn.end：界面立即收尾；后置钩子（记忆抽取）慢或失败都不再拖住回合
            yield await self._emit(
                session_id,
                EventType.TURN_END,
                {
                    "status": "completed",
                    "usage": usage.model_dump() if usage is not None else None,
                },
            )
            try:
                await self._hooks.run("post_execute", post)
            except Exception:
                logger.warning("回合后置钩子失败", exc_info=True)
            return

    async def _skip_calls(self, session_id: int, calls: list[ToolCall]) -> AsyncIterator[TurnEvent]:
        """回合取消时为未执行调用补显式结果，保证 tool_calls / tool 消息配对。"""
        for call in calls:
            yield await self._emit(
                session_id,
                EventType.TOOL_RESULT,
                {"name": call.name, "error": "回合已取消，调用未执行", "tool_call_id": call.id},
            )
            yield await self._emit(
                session_id,
                EventType.TOOL_MESSAGE,
                {
                    "tool_call_id": call.id,
                    "name": call.name,
                    "content": f"工具 {call.name} 未执行：回合已取消",
                },
            )

    async def _inject_queue(self, session_id: int) -> AsyncIterator[TurnEvent]:
        """工具批次结束后消费排队消息：作为插话写入同一回合的下一步。"""
        for row in await queue_coordinator.claim_pending(session_id):
            if not await queue_coordinator.is_claimed(session_id, row.id):
                continue
            yield await self._emit(
                session_id,
                EventType.USER_MESSAGE,
                {"content": row.content, "steering": True, "queue_id": str(row.id)},
            )
            await queue_coordinator.mark_injected(session_id, row.id)

    async def _execute_tool(
        self,
        session_id: int,
        turn_seq: int,
        step: int,
        call: ToolCall,
        work_dir: str,
        user_content: str,
        preset: str,
        kind: str,
        control: TurnControl,
        checkpoint_id: int | None = None,
        calls: list[ToolCall] | None = None,
        call_index: int = 0,
        emit_call: bool = True,
        approved_outcome: str | None = None,
    ) -> AsyncIterator[TurnEvent]:
        """执行一步工具：pre-execute 钩子 fail-closed；权限门裁决（allow 执行 / deny 拒绝 / ask 挂起）。"""
        if control.cancelled:
            return
        ctx = TurnContext(
            session_id=session_id,
            turn_seq=turn_seq,
            step=step,
            action="pre_execute",
            tool_name=call.name,
            payload={"arguments": call.arguments},
        )
        await self._hooks.run("pre_execute", ctx)
        if emit_call:
            yield await self._emit(
                session_id,
                EventType.TOOL_CALL,
                {"name": call.name, "arguments": call.arguments, "tool_call_id": call.id},
            )
        tool = self._tools.get(call.name)
        if tool is None:
            yield await self._emit(
                session_id, EventType.TOOL_RESULT, {"name": call.name, "error": "未知工具", "tool_call_id": call.id}
            )
            yield await self._emit(
                session_id,
                EventType.TOOL_MESSAGE,
                {"tool_call_id": call.id, "name": call.name, "content": f"工具 {call.name} 返回：未知工具"},
            )
            return
        if approved_outcome == "rejected":
            yield await self._emit(
                session_id,
                EventType.TOOL_RESULT,
                {"name": call.name, "error": "用户拒绝", "tool_call_id": call.id},
            )
            yield await self._emit(
                session_id,
                EventType.TOOL_MESSAGE,
                {
                    "tool_call_id": call.id,
                    "name": call.name,
                    "content": f"工具 {call.name} 被用户拒绝",
                },
            )
            return
        if approved_outcome is None:
            raw_args = (
                cast(dict[str, object], json.loads(call.arguments)) if call.arguments else cast(dict[str, object], {})
            )
            decision = await self._permission.decide(tool, work_dir, raw_args, preset)
            if decision.verdict == "deny":
                yield await self._emit(
                    session_id,
                    EventType.TOOL_RESULT,
                    {"name": call.name, "error": f"已拒绝：{decision.reason}", "tool_call_id": call.id},
                )
                yield await self._emit(
                    session_id,
                    EventType.TOOL_MESSAGE,
                    {
                        "tool_call_id": call.id,
                        "name": call.name,
                        "content": f"工具 {call.name} 被拒绝：{decision.reason}",
                    },
                )
                return
            if decision.verdict == "ask":
                if kind == "sub":
                    # 子会话（后台任务）审批自动拒绝：不弹审批卡，把限制写进结果
                    yield await self._emit(
                        session_id,
                        EventType.TOOL_RESULT,
                        {"name": call.name, "error": "后台任务审批自动拒绝：需要更高权限时请在主会话执行"},
                    )
                    yield await self._emit(
                        session_id,
                        EventType.TOOL_MESSAGE,
                        {
                            "tool_call_id": call.id,
                            "name": call.name,
                            "content": f"工具 {call.name} 被自动拒绝：后台任务不能弹审批，请把限制写进最终回复",
                        },
                    )
                    return
                request_id = uuid4().hex
                self._pending[(session_id, request_id)] = PendingBatch(
                    turn_seq=turn_seq,
                    step=step,
                    calls=calls if calls is not None else [call],
                    next_index=call_index,
                    work_dir=work_dir,
                    user_content=user_content,
                    call_emitted=emit_call,
                )
                yield await self._emit(
                    session_id,
                    EventType.PERMISSION_REQUIRED,
                    {"request_id": request_id, "name": call.name, "reason": decision.reason},
                )
                raise PendingApproval(request_id)
        try:
            raw_args = (
                cast(dict[str, object], json.loads(call.arguments)) if call.arguments else cast(dict[str, object], {})
            )

            async def _snapshot(action: str, path: str) -> None:
                if checkpoint_id is not None:
                    await snapshot_file(checkpoint_id, work_dir, path, action)

            output = await tool.handler(
                raw_args,
                ToolContext(
                    work_dir,
                    self._tool_states.setdefault(session_id, SessionToolState()),
                    preset == "full-access",
                    control.event,
                    _snapshot,
                ),
            )
            yield await self._emit(
                session_id,
                EventType.TOOL_RESULT,
                {"name": call.name, "result": output, "tool_call_id": call.id},
            )
            yield await self._emit(
                session_id,
                EventType.TOOL_MESSAGE,
                {"tool_call_id": call.id, "name": call.name, "content": f"工具 {call.name} 返回：{output}"},
            )
        except Exception as exc:
            logger.warning("工具执行失败：%s %s", call.name, exc)
            yield await self._emit(
                session_id,
                EventType.TOOL_RESULT,
                {"name": call.name, "error": str(exc), "tool_call_id": call.id},
            )
            yield await self._emit(
                session_id,
                EventType.TOOL_MESSAGE,
                {"tool_call_id": call.id, "name": call.name, "content": f"工具 {call.name} 返回错误：{exc}"},
            )

    async def _assemble(
        self,
        session_id: int,
        user_id: int,
        persona: Persona | None,
        work_dir: str,
        query: str,
        turn_seq: int,
        first: bool,
        skill_text: str | None = None,
    ) -> list[ChatMessage]:
        """缓存友好装配：静态前缀 → 历史 → 动态尾巴（运行时/记忆）→ 本轮用户消息。

        静态段逐字节稳定、历史只追加、动态内容全部放尾部：DeepSeek 前缀缓存只匹配
        从首条消息开始的完整相同前缀，动态内容插在历史前会让整段历史每轮 miss。

        首步：历史跳过本轮用户消息（它放在尾部，避免重复），再补运行时/记忆段。
        后续步：历史已包含本轮的 assistant(tool_calls) 与 tool 结果，不能再追加用户消息，
        否则模型会把工具结果当成上一轮、反复重发同一次调用。
        """
        static_texts = [
            load_prompt("behavior"),
            self._capability_prompt(),
            *(
                [load_prompt("sub_session"), load_prompt("source_sub")]
                if persona is None
                else [render_system_prompt(persona), load_prompt("source_chat")]
            ),
        ]
        messages = [ChatMessage(role="system", content=text, tool_calls=[]) for text in static_texts]
        messages.extend(await build_history(session_id, skip_seq=turn_seq + 1 if first else None))
        if not first:
            return messages
        messages.append(ChatMessage(role="system", content=self._runtime_context(work_dir), tool_calls=[]))
        if skill_text is not None:
            messages.append(ChatMessage(role="system", content=skill_text, tool_calls=[]))
        if persona is not None:
            memories = await recall(work_dir, query, self._embedder, user_id=user_id)
            if memories:
                items = [
                    f"{index}. （{memory.kind}，{memory.created_at:%Y-%m-%d}）{memory.content}"
                    for index, memory in enumerate(memories, start=1)
                ]
                messages.append(
                    ChatMessage(role="system", content=render_prompt("memory", items="\n".join(items)), tool_calls=[])
                )
        messages.append(ChatMessage(role="user", content=query, tool_calls=[]))
        return messages

    @staticmethod
    def _runtime_context(work_dir: str) -> str:
        """运行时上下文：时间/工作目录/平台（每轮可变，只能放动态尾巴）。"""
        now = datetime.now().astimezone()
        return render_prompt(
            "runtime",
            time=f"{now:%Y-%m-%d %H:%M:%S %Z}",
            work_dir=os.path.abspath(work_dir),
            platform=platform.system(),
            platform_code=sys.platform,
        )

    def _capability_prompt(self) -> str:
        """能力与规则段：工具清单（来自注册表白名单）与执行约定（模板在 prompts/capability.md）。"""
        tools = "\n".join(f"- {spec.name}：{spec.description}" for spec in self._tools.specs())
        return render_prompt("capability", tools=tools)

    @staticmethod
    async def _emit(session_id: int, event_type: EventType, payload: dict[str, object]) -> TurnEvent:
        """落日志并产出同构回合事件：日志是唯一事实源，产出流是其投影。"""
        seq = await append_event(session_id, event_type, payload)
        payload["seq"] = seq
        return TurnEvent(type=str(event_type), payload=payload)
