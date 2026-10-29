"""助手 API：人格版本、会话权限档与停止。"""

import pytest
from conftest import db_up
from httpx2 import AsyncClient

from lingxi.core.log import replay_events
from lingxi.domain.persona.models import PersonaFields

FIELDS = {
    "name": "可乐",
    "opening": "你好，我是可乐。",
    "catchphrases": ["明白"],
    "tone": "轻松直接",
    "style": "先结论后细节",
    "boundaries": "先问再动",
    "user_name": "老板",
    "user_profile": "后端开发",
}


async def test_persona_versions_api(client: AsyncClient, engine_fixture: None) -> None:
    """保存=新版本自动启用；启用旧版本切换默认；版本详情可读。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    current = (await client.get("/persona")).json()
    assert current["code"] == 0
    versions_before = (await client.get("/persona/versions")).json()["data"]["versions"]
    max_before = max(int(v["version"]) for v in versions_before)
    base_version = int(current["data"]["version"])
    saved = (await client.put("/persona", json={"fields": FIELDS})).json()
    assert saved["code"] == 0
    new_version = int(saved["data"]["version"])
    assert new_version == max_before + 1
    versions = (await client.get("/persona/versions")).json()["data"]["versions"]
    active = [v["version"] for v in versions if v["active"]]
    assert active == [new_version]
    detail = (await client.get(f"/persona/versions/{base_version}")).json()
    assert detail["data"]["version"] == base_version
    activated = (await client.post(f"/persona/versions/{base_version}/activate")).json()
    assert activated["code"] == 0
    again = (await client.get("/persona")).json()["data"]["version"]
    assert again == base_version


async def test_session_permission_preset(client: AsyncClient, engine_fixture: None) -> None:
    """建会话带默认档；切换只影响本会话并落 permission/policy 事件。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    created = (await client.post("/sessions", json={"work_dir": "D:\\tmp", "title": "档位"})).json()
    sid = int(created["data"]["id"])
    got = (await client.get(f"/sessions/{sid}")).json()["data"]
    assert got["permission_preset"] == "standard"
    changed = (await client.put(f"/sessions/{sid}/permission", json={"preset": "loose"})).json()
    assert changed["code"] == 0
    got2 = (await client.get(f"/sessions/{sid}")).json()["data"]
    assert got2["permission_preset"] == "loose"
    policies = [e for e in await replay_events(sid) if e.type == "permission/policy"]
    assert policies and policies[-1].payload["preset"] == "loose"
    bad = (await client.put(f"/sessions/{sid}/permission", json={"preset": "nonsense"})).json()
    assert bad["code"] == 60001


async def test_turn_stop_api(client: AsyncClient, engine_fixture: None) -> None:
    """停止接口：无进行中回合时安全返回成功。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    created = (await client.post("/sessions", json={"work_dir": "D:\\tmp", "title": "停止"})).json()
    sid = int(created["data"]["id"])
    resp = (await client.post(f"/sessions/{sid}/turns/stop")).json()
    assert resp["code"] == 0


async def test_session_usage_after_turn(client: AsyncClient, engine_fixture: None) -> None:
    """用量汇总：回合完成后调用明细归集到会话（次数与 token）。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    created = (await client.post("/sessions", json={"work_dir": "D:\\tmp", "title": "用量"})).json()
    sid = int(created["data"]["id"])
    async with client.stream("POST", f"/sessions/{sid}/turns", json={"content": "hi"}) as stream:
        async for _line in stream.aiter_lines():
            pass
    usage = (await client.get(f"/sessions/{sid}/usage")).json()["data"]
    assert usage["calls"] >= 1
    assert usage["prompt_tokens"] >= 9
    assert usage["completion_tokens"] >= 4


async def test_title_auto_rename_and_delete(client: AsyncClient, engine_fixture: None) -> None:
    """标题：首条消息取前 10 字生成；可重命名；逻辑删除后读不到、列表不显示。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    created = (await client.post("/sessions", json={"work_dir": "D:\\tmp"})).json()
    sid = int(created["data"]["id"])
    assert (await client.get(f"/sessions/{sid}")).json()["data"]["title"] == ""
    async with client.stream(
        "POST", f"/sessions/{sid}/turns", json={"content": "你好，帮我看看这个仓库的结构"}
    ) as stream:
        async for _line in stream.aiter_lines():
            pass
    title = (await client.get(f"/sessions/{sid}")).json()["data"]["title"]
    assert title == "你好，帮我看看这个仓库的"[:10]

    renamed = (await client.patch(f"/sessions/{sid}", json={"title": "仓库结构检查"})).json()
    assert renamed["code"] == 0
    assert (await client.get(f"/sessions/{sid}")).json()["data"]["title"] == "仓库结构检查"

    assert (await client.delete(f"/sessions/{sid}")).json()["code"] == 0
    gone = (await client.get(f"/sessions/{sid}")).json()
    assert gone["code"] == 60002
    rows = (await client.get("/sessions")).json()["data"]["sessions"]
    assert all(row["id"] != str(sid) for row in rows)


async def test_delete_workspace_soft_deletes_records_only(client: AsyncClient, engine_fixture: None, tmp_path) -> None:
    """删除工作目录分组：只逻辑删除会话记录，磁盘目录原样保留。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    work_dir = str(tmp_path)
    first = (await client.post("/sessions", json={"work_dir": work_dir})).json()["data"]["id"]
    second = (await client.post("/sessions", json={"work_dir": work_dir})).json()["data"]["id"]
    other = (await client.post("/sessions", json={"work_dir": "D:\\tmp", "title": "别处"})).json()["data"]["id"]
    removed = (await client.delete("/workspaces", params={"work_dir": work_dir})).json()
    assert removed["code"] == 0
    assert removed["data"]["deleted"] == 2
    rows = (await client.get("/sessions")).json()["data"]["sessions"]
    ids = {row["id"] for row in rows}
    assert str(first) not in ids and str(second) not in ids
    assert str(other) in ids
    assert (tmp_path).is_dir()


async def test_fs_dirs_browse(client: AsyncClient, engine_fixture: None, tmp_path) -> None:
    """目录浏览：列出子目录与上级路径；不带 path 时给盘符。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    (tmp_path / "alpha").mkdir()
    (tmp_path / "beta").mkdir()
    body = (await client.get("/fs/dirs", params={"path": str(tmp_path)})).json()
    assert body["code"] == 0
    names = [entry["name"] for entry in body["data"]["entries"]]
    assert {"alpha", "beta"} <= set(names)
    assert body["data"]["parent"]
    roots = (await client.get("/fs/dirs")).json()["data"]["entries"]
    assert roots


def test_persona_fields_model_shape() -> None:
    """八字段模型可直接由接口入参构造。"""
    fields = PersonaFields(**FIELDS)
    assert fields.name == "可乐"
    assert len(PersonaFields.model_fields) == 8
