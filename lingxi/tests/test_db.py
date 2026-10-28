"""数据库接入：六表存在性与基本读写（依赖 docker compose 起 postgres）。"""

import pytest
from sqlalchemy import inspect, text
from sqlalchemy.exc import IntegrityError

from lingxi.app.config import Settings
from lingxi.db.engine import dispose_engine, init_engine, session_scope
from lingxi.db.models import AiSession, SessionEvent

TABLE_NAMES = {"ai_session", "memory", "model_call", "permission_rule", "persona_version", "session_event"}


@pytest.fixture
async def engine_fixture() -> None:
    """测试期引擎：初始化并随用例结束释放。"""
    init_engine(Settings().database_url)
    yield
    await dispose_engine()


async def db_up() -> bool:
    """数据库可达性探测：不可达时用例跳过并提示起库。"""
    try:
        async with session_scope() as s:
            await s.execute(text("select 1"))
        return True
    except Exception:
        return False


async def test_six_tables_exist(engine_fixture: None) -> None:
    """迁移后六张表齐备。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres 再跑 alembic upgrade head")
    async with session_scope() as s:
        conn = await s.connection()
        names = set(await conn.run_sync(lambda sc: inspect(sc).get_table_names()))
    assert names >= TABLE_NAMES


async def test_session_roundtrip(engine_fixture: None) -> None:
    """会话写入后可按主键读回。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres 再跑 alembic upgrade head")
    async with session_scope() as s:
        row = AiSession(work_dir="D:\\tmp", title="冒烟会话")
        s.add(row)
        await s.commit()
        session_id = row.id
    async with session_scope() as s:
        loaded = await s.get(AiSession, session_id)
    assert loaded is not None
    assert loaded.work_dir == "D:\\tmp"
    assert loaded.title == "冒烟会话"


async def test_event_seq_unique(engine_fixture: None) -> None:
    """事件日志同会话同 seq 重复追加被唯一键拒绝。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres 再跑 alembic upgrade head")
    async with session_scope() as s:
        row = AiSession(work_dir="D:\\tmp", title="防重")
        s.add(row)
        await s.flush()
        s.add(SessionEvent(session_id=row.id, seq=1, type="turn/start", payload={}))
        s.add(SessionEvent(session_id=row.id, seq=1, type="turn/start", payload={}))
        with pytest.raises(IntegrityError):
            await s.flush()
