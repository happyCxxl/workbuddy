"""会话检查点：按回合保存文件快照，并支持回退操作。"""

import hashlib
import os
import shutil
from datetime import UTC, datetime, timedelta
from pathlib import Path
from uuid import uuid4

from sqlalchemy import delete, select

from lingxi.core.log import EventType, append_event
from lingxi.db.engine import session_scope
from lingxi.db.models import AiSession, SessionCheckpoint


def _root() -> Path:
    """快照根目录：默认用户目录，测试可用环境变量覆盖。"""
    configured = os.environ.get("WORKBUDDY_CHECKPOINT_DIR")
    return Path(configured) if configured else Path.home() / ".workbuddy" / "file-history"


def _checkpoint_dir(session_id: int, checkpoint_id: int) -> Path:
    """单个检查点的快照目录。"""
    return _root() / str(session_id) / str(checkpoint_id)


def _resolve(work_dir: str, relative: str) -> Path:
    """把相对路径限制在工作目录内。"""
    base = Path(work_dir).resolve()
    target = (base / relative).resolve()
    if target != base and not target.is_relative_to(base):
        raise ValueError(f"路径越界：{relative}")
    return target


def _is_link(path: Path) -> bool:
    """符号链接或硬链接不参与快照与恢复。"""
    if path.is_symlink():
        return True
    try:
        return path.stat().st_nlink > 1
    except OSError:
        return False


async def create_checkpoint(session_id: int, turn_seq: int, prompt: str) -> int:
    """回合开始创建检查点；同时清理超出保留策略的旧检查点。"""
    async with session_scope() as s:
        row = SessionCheckpoint(session_id=session_id, turn_seq=turn_seq, prompt=prompt, files=[])
        s.add(row)
        await s.commit()
        await s.refresh(row)
        checkpoint_id = row.id
    await prune(session_id)
    return checkpoint_id


async def snapshot_file(checkpoint_id: int, work_dir: str, relative: str, action: str) -> None:
    """文件第一次被修改前保存原内容；支持工作区外的绝对路径。"""
    async with session_scope() as s:
        row = await s.get(SessionCheckpoint, checkpoint_id)
        if row is None:
            return
        files = list(row.files)
        absolute = Path(relative).is_absolute()
        path_key = str(Path(relative)) if absolute else relative
        if any(str(item.get("path")) == path_key for item in files):
            return
        target = Path(relative) if absolute else _resolve(work_dir, relative)
        if _is_link(target):
            files.append({"path": path_key, "absolute": absolute, "action": action, "skipped": "symlink-or-hardlink"})
            row.files = files
            await s.commit()
            return
        existed = target.is_file()
        snapshot_name = uuid4().hex if existed else None
        if existed and snapshot_name is not None:
            directory = _checkpoint_dir(row.session_id, checkpoint_id)
            directory.mkdir(parents=True, exist_ok=True)
            data = target.read_bytes()
            (directory / snapshot_name).write_bytes(data)
            digest = hashlib.sha256(data).hexdigest()
        else:
            digest = None
        files.append(
            {
                "path": path_key,
                "absolute": absolute,
                "action": action,
                "existed": existed,
                "snapshot": snapshot_name,
                "sha256": digest,
            }
        )
        row.files = files
        await s.commit()


async def list_checkpoints(session_id: int) -> list[SessionCheckpoint]:
    """会话检查点列表：新的在前，只返回有文件改动的条目给回退菜单。"""
    async with session_scope() as s:
        rows = (
            await s.scalars(
                select(SessionCheckpoint)
                .where(SessionCheckpoint.session_id == session_id)
                .order_by(SessionCheckpoint.turn_seq.desc())
            )
        ).all()
    return list(rows)


async def rewind(session_id: int, checkpoint_id: int, mode: str) -> dict[str, object]:
    """回退到检查点：mode=code|conversation|both。

    文件恢复到目标回合开始前的状态；对话回退通过追加 rewind 事件实现。
    """
    async with session_scope() as s:
        target = await s.get(SessionCheckpoint, checkpoint_id)
        if target is None or target.session_id != session_id:
            raise ValueError("检查点不存在")
        session = await s.get(AiSession, session_id)
        if session is None:
            raise ValueError("会话不存在")
        work_dir = session.work_dir
        rows = (
            await s.scalars(
                select(SessionCheckpoint)
                .where(
                    SessionCheckpoint.session_id == session_id,
                    SessionCheckpoint.turn_seq >= target.turn_seq,
                )
                .order_by(SessionCheckpoint.turn_seq)
            )
        ).all()
    restored = 0
    skipped: list[str] = []
    if mode in ("code", "both"):
        seen: set[str] = set()
        for row in rows:
            for item in row.files:
                relative = str(item.get("path") or "")
                if not relative or relative in seen:
                    continue
                seen.add(relative)
                if item.get("skipped"):
                    skipped.append(relative)
                    continue
                target_path = Path(relative) if bool(item.get("absolute")) else _resolve(work_dir, relative)
                if _is_link(target_path):
                    skipped.append(relative)
                    continue
                if bool(item.get("existed")):
                    snapshot = item.get("snapshot")
                    source = _checkpoint_dir(session_id, row.id) / str(snapshot)
                    if not source.is_file():
                        skipped.append(relative)
                        continue
                    target_path.parent.mkdir(parents=True, exist_ok=True)
                    target_path.write_bytes(source.read_bytes())
                elif target_path.exists():
                    target_path.unlink()
                restored += 1
    if mode in ("conversation", "both"):
        await append_event(
            session_id,
            EventType.REWIND,
            {
                "checkpoint_id": checkpoint_id,
                "target_turn_seq": target.turn_seq,
                "mode": mode,
                "apply_conversation": True,
            },
        )
    return {"restored": restored, "skipped": skipped, "prompt": target.prompt, "mode": mode}


async def prune(session_id: int) -> None:
    """保留最近 100 个检查点、30 天：删除行与快照目录。"""
    async with session_scope() as s:
        rows = (
            await s.scalars(
                select(SessionCheckpoint)
                .where(SessionCheckpoint.session_id == session_id)
                .order_by(SessionCheckpoint.turn_seq.desc())
            )
        ).all()
        cutoff = datetime.now(UTC) - timedelta(days=30)
        expired = list(rows[100:])
        expired.extend(row for row in rows[:100] if row.created_at < cutoff)
        if not expired:
            return
        for row in expired:
            shutil.rmtree(_checkpoint_dir(session_id, row.id), ignore_errors=True)
        await s.execute(delete(SessionCheckpoint).where(SessionCheckpoint.id.in_([row.id for row in expired])))
        await s.commit()
