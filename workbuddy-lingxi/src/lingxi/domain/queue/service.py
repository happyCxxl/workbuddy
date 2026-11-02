"""会话输入队列：运行中排队的消息，支持编辑、撤回与逐步消费。"""

from sqlalchemy import delete, func, select

from lingxi.db.engine import session_scope
from lingxi.db.models import SessionInputQueue


async def enqueue(session_id: int, content: str) -> int:
    """追加一条排队消息，返回队列项 ID。"""
    async with session_scope() as s:
        max_position = await s.scalar(
            select(func.max(SessionInputQueue.position)).where(
                SessionInputQueue.session_id == session_id,
                SessionInputQueue.status == "pending",
            )
        )
        row = SessionInputQueue(
            session_id=session_id,
            content=content,
            status="pending",
            position=(max_position or 0) + 1,
        )
        s.add(row)
        await s.commit()
        await s.refresh(row)
        return row.id


async def list_pending(session_id: int) -> list[SessionInputQueue]:
    """按排序位列待消费消息。"""
    async with session_scope() as s:
        rows = await s.scalars(
            select(SessionInputQueue)
            .where(
                SessionInputQueue.session_id == session_id,
                SessionInputQueue.status == "pending",
            )
            .order_by(SessionInputQueue.position, SessionInputQueue.id)
        )
        return list(rows)


async def reorder(session_id: int, item_ids: list[int]) -> bool:
    """按给定 ID 顺序重写排序位；只接受同一会话的全部待消费项。"""
    async with session_scope() as s:
        rows = list(
            await s.scalars(
                select(SessionInputQueue).where(
                    SessionInputQueue.session_id == session_id,
                    SessionInputQueue.status == "pending",
                )
            )
        )
        if {row.id for row in rows} != set(item_ids):
            return False
        by_id = {row.id: row for row in rows}
        for index, item_id in enumerate(item_ids):
            by_id[item_id].position = index + 1
        await s.commit()
        return True


async def update_item(session_id: int, item_id: int, content: str) -> bool:
    """编辑排队消息；只允许编辑仍待消费的项。"""
    async with session_scope() as s:
        row = await s.get(SessionInputQueue, item_id)
        if row is None or row.session_id != session_id or row.status != "pending":
            return False
        row.content = content
        await s.commit()
        return True


async def cancel_item(session_id: int, item_id: int) -> bool:
    """撤回排队消息。"""
    async with session_scope() as s:
        row = await s.get(SessionInputQueue, item_id)
        if row is None or row.session_id != session_id or row.status != "pending":
            return False
        await s.delete(row)
        await s.commit()
        return True


async def claim_pending(session_id: int) -> list[SessionInputQueue]:
    """把当前待消费项标记为已认领，返回快照供本步注入。"""
    async with session_scope() as s:
        rows = list(
            await s.scalars(
                select(SessionInputQueue)
                .where(
                    SessionInputQueue.session_id == session_id,
                    SessionInputQueue.status == "pending",
                )
                .order_by(SessionInputQueue.position, SessionInputQueue.id)
            )
        )
        for row in rows:
            row.status = "claimed"
        if rows:
            await s.commit()
        return rows


async def claim_item(session_id: int, item_id: int) -> SessionInputQueue | None:
    """认领一条指定队列项；不存在或已认领时返回 None。"""
    async with session_scope() as s:
        row = await s.get(SessionInputQueue, item_id)
        if row is None or row.session_id != session_id or row.status != "pending":
            return None
        row.status = "claimed"
        await s.commit()
        return row


async def clear_session(session_id: int) -> None:
    """会话删除时清理未消费队列。"""
    async with session_scope() as s:
        await s.execute(delete(SessionInputQueue).where(SessionInputQueue.session_id == session_id))
        await s.commit()
