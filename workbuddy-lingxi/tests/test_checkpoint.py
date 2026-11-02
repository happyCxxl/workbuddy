"""会话检查点：文件快照、回退与对话截断。"""

import pytest

from lingxi.core.log import EventType, append_event
from lingxi.domain.checkpoint.service import create_checkpoint, rewind, snapshot_file
from lingxi.domain.session.service import build_history, create_session


@pytest.mark.usefixtures("engine_fixture")
async def test_rewind_restores_file_and_removes_created(tmp_path, monkeypatch) -> None:
    """回退操作恢复原文件内容，并删除检查点之后新建的文件。"""
    monkeypatch.setenv("WORKBUDDY_CHECKPOINT_DIR", str(tmp_path / "snapshots"))
    work = tmp_path / "work"
    work.mkdir()
    existing = work / "app.txt"
    existing.write_text("original", encoding="utf-8")
    session_id = await create_session(str(work), "检查点")
    checkpoint_id = await create_checkpoint(session_id, 1, "改文件")
    await snapshot_file(checkpoint_id, str(work), "app.txt", "write")
    existing.write_text("changed", encoding="utf-8")
    await snapshot_file(checkpoint_id, str(work), "new.txt", "write")
    (work / "new.txt").write_text("new", encoding="utf-8")

    result = await rewind(session_id, checkpoint_id, "code")

    assert result["restored"] == 2
    assert existing.read_text(encoding="utf-8") == "original"
    assert not (work / "new.txt").exists()


@pytest.mark.usefixtures("engine_fixture")
async def test_rewind_conversation_truncates_history(tmp_path, monkeypatch) -> None:
    """对话回退：目标回合之后、rewind 事件之前的消息不再进入历史。"""
    monkeypatch.setenv("WORKBUDDY_CHECKPOINT_DIR", str(tmp_path / "snapshots"))
    work = tmp_path / "work"
    work.mkdir()
    session_id = await create_session(str(work), "对话回退")
    await append_event(session_id, EventType.USER_MESSAGE, {"content": "第一问"})
    await append_event(session_id, EventType.ASSISTANT_MESSAGE, {"content": "第一答"})
    start = await append_event(session_id, EventType.TURN_START, {})
    await append_event(session_id, EventType.USER_MESSAGE, {"content": "第二问"})
    await append_event(session_id, EventType.ASSISTANT_MESSAGE, {"content": "第二答"})
    checkpoint_id = await create_checkpoint(session_id, start, "第二问")

    await rewind(session_id, checkpoint_id, "conversation")

    history = await build_history(session_id)
    assert [message.content for message in history] == ["第一问", "第一答"]
