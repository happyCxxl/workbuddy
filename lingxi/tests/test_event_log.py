"""事件日志：追加单调 seq、按 seq 读、重放与失败语义。"""

import pytest
from sqlalchemy import text
from sqlalchemy.exc import IntegrityError

from lingxi.app.config import Settings
from lingxi.core import log as log_module
from lingxi.core.log import EventType, append_event, read_events, replay_events
from lingxi.db.engine import dispose_engine, init_engine, session_scope
from lingxi.db.models import AiSession


@pytest.fixture
async def engine_fixture() -> None:
    """测试期引擎：初始化并随用例结束释放。"""
    init_engine(Settings(**{}).database.url)
    yield
    await dispose_engine()


async def db_up() -> bool:
    """数据库可达性探测：不可达时用例跳过。"""
    try:
        async with session_scope() as s:
            await s.execute(text("select 1"))
        return True
    except Exception:
        return False


async def make_session(title: str) -> int:
    """造一个会话并返回主键。"""
    async with session_scope() as s:
        row = AiSession(work_dir="D:\\tmp", title=title)
        s.add(row)
        await s.commit()
        return row.id


async def test_append_assigns_monotonic_seq(engine_fixture: None) -> None:
    """连续追加三条事件，seq 从 1 单调递增。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    sid = await make_session("单调")
    seqs = [
        await append_event(sid, EventType.TEXT_DELTA, {"text": "你"}),
        await append_event(sid, EventType.TEXT_DELTA, {"text": "好"}),
        await append_event(sid, EventType.TURN_END, {}),
    ]
    assert seqs == [1, 2, 3]


async def test_read_after_seq_with_limit(engine_fixture: None) -> None:
    """按 seq 过滤读取并受 limit 约束。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    sid = await make_session("过滤")
    for text_value in ("一", "二", "三"):
        await append_event(sid, EventType.TEXT_DELTA, {"text": text_value})
    events = await read_events(sid, after_seq=1, limit=1)
    assert [e.seq for e in events] == [2]
    assert events[0].payload == {"text": "二"}


async def test_replay_returns_all_in_order(engine_fixture: None) -> None:
    """重放返回 after_seq 之后全部事件且顺序与内容完整。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    sid = await make_session("重放")
    for text_value in ("一", "二"):
        await append_event(sid, EventType.TEXT_DELTA, {"text": text_value})
    await append_event(sid, EventType.TURN_END, {})
    events = await replay_events(sid)
    assert [(e.seq, e.type) for e in events] == [
        (1, "text.delta"),
        (2, "text.delta"),
        (3, "turn.end"),
    ]


async def test_append_fails_closed_on_integrity_error(engine_fixture: None, monkeypatch: pytest.MonkeyPatch) -> None:
    """插入唯一键冲突且无同 seq 记录时原样上抛（fail-closed）。"""

    async def fake_insert(self: log_module.EventRepo, event: object) -> None:
        del self, event
        raise IntegrityError("dup", None, Exception("dup"))

    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    monkeypatch.setattr(log_module.EventRepo, "insert", fake_insert)
    sid = await make_session("失败")
    with pytest.raises(IntegrityError):
        await append_event(sid, EventType.TEXT_DELTA, {"text": "x"})
