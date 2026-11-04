"""输入队列：持久化、编辑、撤回与认领。"""

import pytest
from httpx2 import AsyncClient

from lingxi.domain.queue import service
from lingxi.domain.queue.coordinator import coordinator
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
async def test_queue_reorder(tmp_path) -> None:
    """重排后按新顺序列出与认领。"""
    session_id = await create_session(str(tmp_path), "重排")
    first = await service.enqueue(session_id, "第一条")
    second = await service.enqueue(session_id, "第二条")
    third = await service.enqueue(session_id, "第三条")

    assert await service.reorder(session_id, [third, first, second]) is True
    assert [row.content for row in await service.list_pending(session_id)] == ["第三条", "第一条", "第二条"]
    assert [row.content for row in await service.claim_pending(session_id)] == ["第三条", "第一条", "第二条"]


@pytest.mark.usefixtures("engine_fixture")
async def test_cancel_claimed_before_injection(tmp_path) -> None:
    """已认领但尚未注入的消息仍可撤回；注入完成后不能再撤回。"""
    session_id = await create_session(str(tmp_path), "撤回竞态")
    item_id = await service.enqueue(session_id, "撤回我")
    assert [row.id for row in await service.claim_pending(session_id)] == [item_id]

    assert await service.cancel_item(session_id, item_id) is True
    assert await service.is_claimed(session_id, item_id) is False
    assert await service.claim_pending(session_id) == []

    injected_id = await service.enqueue(session_id, "已注入")
    await service.claim_pending(session_id)
    await service.mark_injected(session_id, injected_id)
    assert await service.cancel_item(session_id, injected_id) is False


@pytest.mark.usefixtures("engine_fixture")
async def test_coordinator_reports_cancel_reason(tmp_path) -> None:
    """单写者返回明确失败原因，而不是静默成功。"""
    session_id = await create_session(str(tmp_path), "队列原因")
    item_id = await coordinator.enqueue(session_id, "撤回")
    await coordinator.claim_pending(session_id)
    assert await coordinator.cancel(session_id, item_id) == (True, None)

    injected = await coordinator.enqueue(session_id, "已注入")
    await coordinator.claim_pending(session_id)
    await coordinator.mark_injected(session_id, injected)
    assert await coordinator.cancel(session_id, injected) == (False, "already_injected")
    assert await coordinator.cancel(session_id, 999999) == (False, "not_found")


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
