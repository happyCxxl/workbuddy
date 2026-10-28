"""后台任务服务：建子会话、状态流转、列表读取与重启清理（子会话是干净上下文的任务执行器）。"""

from sqlalchemy import func, select

from lingxi.contract import ApiError, ErrorCode
from lingxi.db.engine import session_scope
from lingxi.db.models import AiSession, SubSession
from lingxi.db.repo.session_repo import SessionRepo

MAX_RUNNING = 3
"""同一父会话同时运行的后台任务上限。"""


def _title_of(title: str, task: str) -> str:
    """标题：未填时取任务前 40 字。"""
    cleaned = title.strip()
    return cleaned if cleaned else task.strip()[:40]


async def create_sub_session(parent_session_id: int, title: str, task: str) -> SubSession:
    """派生后台任务：子会话干净上下文（无人格绑定）、继承父目录与权限档；禁止再嵌套。"""
    async with session_scope() as s:
        parent = await SessionRepo(s).get(parent_session_id)
        if parent is None:
            raise ApiError(ErrorCode.NOT_FOUND, f"会话不存在：{parent_session_id}")
        if parent.kind != "chat":
            raise ApiError(ErrorCode.INVALID_ARGUMENT, "后台任务不能再开启后台任务")
        running = await s.scalar(
            select(func.count())
            .select_from(SubSession)
            .where(SubSession.parent_session_id == parent_session_id, SubSession.status == "running")
        )
        if (running or 0) >= MAX_RUNNING:
            raise ApiError(ErrorCode.INVALID_ARGUMENT, f"后台任务过多（最多 {MAX_RUNNING} 个运行中）")
        child = AiSession(
            work_dir=parent.work_dir,
            title=_title_of(title, task),
            permission_preset=parent.permission_preset,
            parent_id=parent_session_id,
            kind="sub",
            persona_version_id=None,
        )
        s.add(child)
        await s.flush()
        row = SubSession(
            parent_session_id=parent_session_id,
            child_session_id=child.id,
            title=_title_of(title, task),
            task=task,
            status="running",
        )
        s.add(row)
        await s.commit()
        return row


async def finish_sub_session(sub_session_id: int, status: str, result: str) -> None:
    """收尾：写状态与结果摘要（completed / failed）。"""
    async with session_scope() as s:
        row = await s.get(SubSession, sub_session_id)
        if row is None:
            return
        row.status = status
        row.result = result
        await s.commit()


async def list_sub_sessions(parent_session_id: int) -> list[SubSession]:
    """父会话的后台任务列表：新建在前。"""
    async with session_scope() as s:
        stmt = (
            select(SubSession).where(SubSession.parent_session_id == parent_session_id).order_by(SubSession.id.desc())
        )
        return list((await s.scalars(stmt)).all())


async def get_sub_session(sub_session_id: int) -> SubSession:
    """单个后台任务：不存在按登记错误码失败。"""
    async with session_scope() as s:
        row = await s.get(SubSession, sub_session_id)
    if row is None:
        raise ApiError(ErrorCode.NOT_FOUND, f"后台任务不存在：{sub_session_id}")
    return row


async def mark_stale_running() -> int:
    """启动清理：进程重启后 running 不可能续跑，全部标记失败；返回条数。"""
    async with session_scope() as s:
        rows = list((await s.scalars(select(SubSession).where(SubSession.status == "running"))).all())
        for row in rows:
            row.status = "failed"
            row.result = "服务重启，任务中断"
        await s.commit()
        return len(rows)
