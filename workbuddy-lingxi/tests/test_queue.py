"""输入队列：持久化、编辑、撤回与认领。"""

import pytest
from httpx import AsyncClient

from lingxi.domain.queue import service
from lingxi.domain.session.service import create_session


@pytest.mark.usefixtures("engine_fixture")
async def test_queue_lifecycle(tmp_path) -> None:
    """排队、编辑、撤回与认领按写入顺序工作。"""
    session_id = await create_session(str(tmp_path), "队列")
    first = await service.enqueue(session_id, "第一条")
    second = await service.enqueue(session_id, "第二条")

    rows = await service.list_pending(session_id)
    assert [row.content for row in rows] == ["第一条", "第二条"]
    assert await service.update_item(session_id, first, "改过") is True
    assert await service.cancel_item(session_id, second) is True
    assert [row.content for row in await service.list_pending(session_id)] == ["改过"]

    claimed = await service.claim_pending(session_id)
    assert [row.content for row in claimed] == ["改过"]
    assert await service.list_pending(session_id) == []


@pytest.mark.usefixtures("engine_fixture")
async def test_queue_api(client: AsyncClient, tmp_path) -> None:
    """队列接口：新增、列表、编辑、撤回。"""
    session_id = await create_session(str(tmp_path), "队列接口")

    created = await client.post(f"/sessions/{session_id}/queue", json={"content": "排队"})
    item_id = created.json()["data"]["id"]
    listed = await client.get(f"/sessions/{session_id}/queue")
    assert [item["content"] for item in listed.json()["data"]["items"]] == ["排队"]

    updated = await client.patch(f"/sessions/{session_id}/queue/{item_id}", json={"content": "改过"})
    assert updated.json()["data"]["updated"] is True
    removed = await client.delete(f"/sessions/{session_id}/queue/{item_id}")
    assert removed.json()["data"]["removed"] is True
    assert (await client.get(f"/sessions/{session_id}/queue")).json()["data"]["items"] == []
