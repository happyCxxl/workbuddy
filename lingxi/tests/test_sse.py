"""SSE 接口：会话创建、回合流式事件与 after_seq 重放。"""

import json
from collections.abc import AsyncIterator

import pytest
from conftest import db_up
from httpx2 import ASGITransport, AsyncClient

from lingxi.app.config import Settings
from lingxi.app.main import create_app


@pytest.fixture
async def client(fake_server: tuple[Settings, list[dict[str, object]]]) -> AsyncIterator[AsyncClient]:
    """应用内嵌客户端：ASGI 传输跑在测试回路，与引擎同回路。"""
    settings, _ = fake_server
    async with AsyncClient(transport=ASGITransport(app=create_app(settings)), base_url="http://test") as c:
        yield c


async def create_session_via_api(client: AsyncClient) -> int:
    """建会话并返回 id。"""
    resp = await client.post("/sessions", json={"work_dir": "D:\\tmp", "title": "SSE 测试"})
    body = resp.json()
    assert body["code"] == 0
    return int(body["data"]["id"])


async def test_turn_stream_emits_typed_events(
    client: AsyncClient, fake_server: tuple[Settings, list[dict[str, object]]], engine_fixture: None
) -> None:
    """回合流：命名事件帧完整，DONE 哨兵收尾。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    sid = await create_session_via_api(client)
    frames: list[tuple[str, object]] = []
    event_type: str | None = None
    async with client.stream("POST", f"/sessions/{sid}/turns", json={"content": "hi"}) as resp:
        assert resp.status_code == 200
        async for line in resp.aiter_lines():
            if line.startswith("event: "):
                event_type = line.removeprefix("event: ")
            elif line.startswith("data: "):
                data = line.removeprefix("data: ")
                if event_type is None:
                    frames.append(("done", data))
                else:
                    frames.append((event_type, json.loads(data) if data != "[DONE]" else data))
                    event_type = None
    types = [t for t, _ in frames if t != "done"]
    assert types[0] == "turn/start"
    assert types.count("text.delta") == 2
    assert "assistant/message" in types
    assert types[-1] == "turn.end"
    assert frames[-1] == ("done", "[DONE]")


async def test_replay_after_seq(
    client: AsyncClient, fake_server: tuple[Settings, list[dict[str, object]]], engine_fixture: None
) -> None:
    """重放：after_seq 之后的事件补齐，按 seq 升序。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    sid = await create_session_via_api(client)
    async with client.stream("POST", f"/sessions/{sid}/turns", json={"content": "hi"}) as resp:
        async for _line in resp.aiter_lines():
            pass
    resp = await client.get(f"/sessions/{sid}/events", params={"after_seq": 0})
    events = resp.json()["data"]["events"]
    seqs = [e["seq"] for e in events]
    assert seqs == sorted(seqs)
    assert events[0]["type"] == "turn/start"
    assert events[-1]["type"] == "turn.end"
    midpoint = events[1]["seq"]
    resp2 = await client.get(f"/sessions/{sid}/events", params={"after_seq": midpoint})
    tail = resp2.json()["data"]["events"]
    assert tail[0]["seq"] == midpoint + 1
    assert tail[-1]["type"] == "turn.end"


async def test_error_turn_emits_error_frame(
    client: AsyncClient, fake_server: tuple[Settings, list[dict[str, object]]], engine_fixture: None
) -> None:
    """失败回合：error 与 turn.end 帧仍下发。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    sid = await create_session_via_api(client)
    types: list[str] = []
    async with client.stream("POST", f"/sessions/{sid}/turns", json={"content": "fail_both"}) as resp:
        async for line in resp.aiter_lines():
            if line.startswith("event: "):
                types.append(line.removeprefix("event: "))
    assert "error" in types
    assert types[-1] == "turn.end"
