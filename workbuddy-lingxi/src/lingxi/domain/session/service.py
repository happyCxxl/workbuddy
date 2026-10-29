"""会话服务：创建、读取与历史投影——事件日志是唯一事实源，历史由事件派生。"""

from typing import cast

from lingxi.contract import ApiError, ErrorCode
from lingxi.core.log import EventType, append_event, replay_events
from lingxi.db.engine import session_scope
from lingxi.db.models import AiSession, PersonaVersion
from lingxi.db.repo.model_call_repo import ModelCallRepo
from lingxi.db.repo.persona_repo import PersonaRepo
from lingxi.db.repo.session_repo import SessionRepo
from lingxi.domain.persona.models import Persona, PersonaFields
from lingxi.domain.ports import ChatMessage, ToolCall


async def create_session(work_dir: str, title: str, preset: str = "standard") -> int:
    """创建会话：绑定当前启用的人格版本与权限档，此后人格编辑不影响本会话。"""
    async with session_scope() as s:
        persona = await PersonaRepo(s).current()
        if persona is None:
            raise ApiError(ErrorCode.NOT_FOUND, "人格未初始化：先执行 alembic upgrade head")
        row = AiSession(work_dir=work_dir, title=title, persona_version_id=persona.id, permission_preset=preset)
        await SessionRepo(s).insert(row)
        await s.commit()
        return row.id


async def set_session_preset(session_id: int, preset: str) -> None:
    """切换本会话权限档：落 permission/policy 审计事件。"""
    async with session_scope() as s:
        row = await SessionRepo(s).get(session_id)
        if row is None:
            raise ApiError(ErrorCode.NOT_FOUND, f"会话不存在：{session_id}")
        row.permission_preset = preset
        await s.commit()
    await append_event(session_id, EventType.PERMISSION_POLICY, {"preset": preset})


async def get_session(session_id: int) -> AiSession:
    """读会话：不存在或已逻辑删除按登记错误码失败。"""
    async with session_scope() as s:
        row = await SessionRepo(s).get(session_id)
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


async def rename_session(session_id: int, title: str) -> None:
    """重命名会话：标题非空，逻辑删除的会话不可改。"""
    cleaned = " ".join(title.split())
    if not cleaned:
        raise ApiError(ErrorCode.INVALID_ARGUMENT, "标题不能为空")
    async with session_scope() as s:
        row = await SessionRepo(s).get(session_id)
        if row is None or row.status == "deleted":
            raise ApiError(ErrorCode.NOT_FOUND, f"会话不存在：{session_id}")
        row.title = cleaned[:255]
        await s.commit()


async def delete_session(session_id: int) -> None:
    """逻辑删除单条会话：行与事件保留（可审计），列表不再显示。"""
    async with session_scope() as s:
        row = await SessionRepo(s).get(session_id)
        if row is None or row.status == "deleted":
            raise ApiError(ErrorCode.NOT_FOUND, f"会话不存在：{session_id}")
        row.status = "deleted"
        await s.commit()


async def delete_workspace(work_dir: str) -> int:
    """逻辑删除某工作目录下的全部对话会话：只改状态，绝不触碰磁盘文件。"""
    async with session_scope() as s:
        rows = await SessionRepo(s).list_active_by_work_dir(work_dir)
        for row in rows:
            row.status = "deleted"
        await s.commit()
        return len(rows)


async def list_sessions(limit: int = 50) -> list[AiSession]:
    """最近会话列表：按更新时间倒序。"""
    async with session_scope() as s:
        return await SessionRepo(s).list_recent(limit)


async def session_usage(session_id: int) -> tuple[int, int, int]:
    """会话用量：调用次数、输入 token、输出 token。"""
    async with session_scope() as s:
        return await ModelCallRepo(s).usage_summary(session_id)


async def persona_of_session(session: AiSession) -> Persona:
    """会话绑定的人格快照：老会话保持创建时版本。"""
    if session.persona_version_id is None:
        raise ApiError(ErrorCode.NOT_FOUND, "会话未绑定人格版本")
    async with session_scope() as s:
        row = await PersonaRepo(s).get_by_id(session.persona_version_id)
    if row is None:
        raise ApiError(ErrorCode.NOT_FOUND, "会话绑定的人格版本不存在")
    return _to_persona(row)


async def build_history(session_id: int) -> list[ChatMessage]:
    """事件投影：compaction 截断点之后的消息序列，供模型对话组装。"""
    events = await replay_events(session_id)
    cutoff = 0
    for event in events:
        if event.type == EventType.COMPACTION:
            cutoff = max(cutoff, cast(int, event.payload.get("cutoff_seq", 0)))
    messages: list[ChatMessage] = []
    for event in events:
        if event.seq <= cutoff:
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
