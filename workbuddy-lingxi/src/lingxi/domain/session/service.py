"""会话服务：创建、读取与历史投影——事件日志是唯一事实源，历史由事件派生。"""

import random
from typing import cast

from lingxi.contract import ApiError, ErrorCode
from lingxi.core.log import EventType, append_event, replay_events
from lingxi.db.engine import session_scope
from lingxi.db.models import AiSession, ModelCall, PersonaVersion
from lingxi.db.repo.model_call_repo import ModelCallRepo
from lingxi.db.repo.persona_repo import PersonaRepo
from lingxi.db.repo.session_repo import SessionRepo
from lingxi.domain.persona.models import DEFAULT_FIELDS, Persona, PersonaFields
from lingxi.domain.ports import ChatMessage, ToolCall
from lingxi.model_gateway import catalog


async def create_session(
    work_dir: str,
    title: str,
    preset: str = "workspace-write",
    workspace_id: int | None = None,
    user_id: int = 1,
    model_id: int | None = None,
) -> int:
    """创建会话：绑定人格、权限档与会话模型；模型未指定时从可用 Chat 模型任选。"""
    if model_id is None:
        available = await catalog.list_available("CHAT")
        model_id = random.choice(available).id if available else None
    async with session_scope() as s:
        persona = await PersonaRepo(s).current(user_id)
        if persona is None:
            # 新用户首次使用：按默认字段懒创建 v1，避免必须先访问人格页
            persona = PersonaVersion(user_id=user_id, version=1, fields=DEFAULT_FIELDS.model_dump(), active=True)
            s.add(persona)
            await s.flush()
        row = AiSession(
            user_id=user_id,
            model_id=model_id,
            work_dir=work_dir,
            title=title,
            persona_version_id=persona.id,
            permission_preset=preset,
            workspace_id=workspace_id,
        )
        await SessionRepo(s).insert(row)
        await s.commit()
        return row.id


async def set_session_preset(session_id: int, preset: str, user_id: int = 1) -> None:
    """切换本会话权限档：落 permission/policy 审计事件。"""
    async with session_scope() as s:
        row = await SessionRepo(s).get(session_id, user_id)
        if row is None:
            raise ApiError(ErrorCode.NOT_FOUND, f"会话不存在：{session_id}")
        row.permission_preset = preset
        await s.commit()
    await append_event(session_id, EventType.PERMISSION_POLICY, {"preset": preset})


async def set_session_model(session_id: int, model_id: int, user_id: int = 1) -> None:
    """切换会话模型：只允许启用中的 CHAT 模型。"""
    available = await catalog.list_available("CHAT")
    if model_id not in {model.id for model in available}:
        raise ApiError(ErrorCode.INVALID_ARGUMENT, f"模型不可用：{model_id}")
    async with session_scope() as s:
        row = await SessionRepo(s).get(session_id, user_id)
        if row is None or row.status == "deleted":
            raise ApiError(ErrorCode.NOT_FOUND, f"会话不存在：{session_id}")
        row.model_id = model_id
        await s.commit()


async def get_session(session_id: int, user_id: int | None = None) -> AiSession:
    """读会话：不存在、已逻辑删除或跨用户都按登记错误码失败。"""
    async with session_scope() as s:
        row = await SessionRepo(s).get(session_id, user_id)
    if row is None or row.status == "deleted":
        raise ApiError(ErrorCode.NOT_FOUND, f"会话不存在：{session_id}")
    return row


async def ensure_title(session_id: int, content: str) -> None:
    """首条消息生成标题：取前 10 字（空白压平），已有标题不覆盖。"""
    cleaned = " ".join(content.split())[:10]
    if not cleaned:
        return
    async with session_scope() as s:
        row = await SessionRepo(s).get(session_id)
        if row is None or row.status != "active" or row.title.strip():
            return
        row.title = cleaned
        await s.commit()


async def rename_session(session_id: int, title: str, user_id: int = 1) -> None:
    """重命名会话：标题非空，逻辑删除的会话不可改。"""
    cleaned = " ".join(title.split())
    if not cleaned:
        raise ApiError(ErrorCode.INVALID_ARGUMENT, "标题不能为空")
    async with session_scope() as s:
        row = await SessionRepo(s).get(session_id, user_id)
        if row is None or row.status == "deleted":
            raise ApiError(ErrorCode.NOT_FOUND, f"会话不存在：{session_id}")
        row.title = cleaned[:255]
        await s.commit()


async def delete_session(session_id: int, user_id: int = 1) -> None:
    """逻辑删除单条会话：行与事件保留（可审计），列表不再显示。"""
    async with session_scope() as s:
        row = await SessionRepo(s).get(session_id, user_id)
        if row is None or row.status == "deleted":
            raise ApiError(ErrorCode.NOT_FOUND, f"会话不存在：{session_id}")
        row.status = "deleted"
        await s.commit()


async def list_sessions(limit: int = 50, user_id: int = 1) -> list[AiSession]:
    """最近会话列表：当前用户，按更新时间倒序。"""
    async with session_scope() as s:
        return await SessionRepo(s).list_recent(limit, user_id)


async def session_usage(session_id: int) -> tuple[int, int, int, int, int]:
    """会话用量：调用次数、输入/输出 token、缓存命中/未命中。"""
    async with session_scope() as s:
        return await ModelCallRepo(s).usage_summary(session_id)


async def list_model_calls(session_id: int) -> list[ModelCall]:
    """会话的模型调用明细：轨迹里按回合/步骤展示模型、token 与耗时。"""
    async with session_scope() as s:
        return await ModelCallRepo(s).list_by_session(session_id)


async def persona_of_session(session: AiSession) -> Persona:
    """会话绑定的人格快照：老会话保持创建时版本。"""
    if session.persona_version_id is None:
        raise ApiError(ErrorCode.NOT_FOUND, "会话未绑定人格版本")
    async with session_scope() as s:
        row = await PersonaRepo(s).get_by_id(session.user_id, session.persona_version_id)
    if row is None:
        raise ApiError(ErrorCode.NOT_FOUND, "会话绑定的人格版本不存在")
    return _to_persona(row)


async def build_history(session_id: int, max_seq: int | None = None, skip_seq: int | None = None) -> list[ChatMessage]:
    """事件投影：compaction 截断点之后的消息序列；max_seq 排除尾部事件，skip_seq 跳过指定一条。"""
    events = await replay_events(session_id)
    cutoff = 0
    for event in events:
        if event.type == EventType.COMPACTION:
            cutoff = max(cutoff, cast(int, event.payload.get("cutoff_seq", 0)))
    messages: list[ChatMessage] = []
    for event in events:
        if event.seq <= cutoff:
            continue
        if max_seq is not None and event.seq > max_seq:
            continue
        if skip_seq is not None and event.seq == skip_seq:
            continue
        if event.type == EventType.USER_MESSAGE:
            messages.append(ChatMessage(role="user", content=str(event.payload.get("content", "")), tool_calls=[]))
        elif event.type == EventType.ASSISTANT_MESSAGE:
            reasoning = event.payload.get("reasoning_content")
            raw_calls = cast(list[dict[str, object]], event.payload.get("tool_calls") or [])
            messages.append(
                ChatMessage(
                    role="assistant",
                    content=str(event.payload.get("content", "")),
                    reasoning_content=str(reasoning) if reasoning else None,
                    tool_calls=[
                        ToolCall(
                            id=str(item.get("id", "")),
                            name=str(item.get("name", "")),
                            arguments=str(item.get("arguments", "")),
                        )
                        for item in raw_calls
                    ],
                )
            )
        elif event.type == EventType.TOOL_MESSAGE:
            messages.append(
                ChatMessage(
                    role="tool",
                    content=str(event.payload.get("content", "")),
                    tool_call_id=str(event.payload.get("tool_call_id") or ""),
                    tool_calls=[],
                )
            )
    return messages


async def compact_history(session_id: int, keep_recent: int = 20) -> None:
    """压缩：追加 compaction 事件标记截断点；日志只追加、不物理删除。"""
    events = await replay_events(session_id)
    if len(events) <= keep_recent:
        return
    cutoff_seq = events[-keep_recent].seq
    await append_event(session_id, EventType.COMPACTION, {"cutoff_seq": cutoff_seq, "summary": ""})


def _to_persona(row: PersonaVersion) -> Persona:
    return Persona(fields=PersonaFields.model_validate(row.fields), version=row.version)
