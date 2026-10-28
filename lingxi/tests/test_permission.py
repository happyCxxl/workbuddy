"""权限：规则匹配、三档预设、高危恒询问与审批停车/恢复重放。"""

from pathlib import Path

import pytest
from conftest import FakeEmbedder, db_up

from lingxi.app.config import Settings
from lingxi.contract import ApiError
from lingxi.core.hooks import HookRegistry
from lingxi.core.loop import TurnRunner
from lingxi.db.engine import session_scope
from lingxi.db.models import PermissionRule
from lingxi.db.repo.permission_repo import PermissionRepo
from lingxi.domain.llm.gateway import LlmGateway
from lingxi.domain.permission.gate import PermissionGate
from lingxi.domain.session.service import create_session
from lingxi.domain.tools.fs import register_fs_tools
from lingxi.domain.tools.registry import ToolRegistry


def make_registry() -> ToolRegistry:
    """标准工具注册表。"""
    registry = ToolRegistry()
    register_fs_tools(registry)
    return registry


def small_settings(settings: Settings, preset: str) -> Settings:
    """以 fake 的 llm/memory 构造小循环配置。"""
    return Settings(
        server={},
        database={},
        tools={},
        memory=settings.memory.model_dump(),
        llm=settings.llm.model_dump(),
        permission={"preset": preset},
        loop={"input_token_budget": 100000, "context_budget_ratio": 0.8, "max_steps": 25},
    )


async def add_rule(tool: str, action: str, level: str = "dir", work_dir: str = "D:\\tmp") -> int:
    """直接入库一条规则并返回 id。"""
    async with session_scope() as s:
        rule = PermissionRule(tool=tool, scope=None, action=action, level=level, work_dir=work_dir)
        await PermissionRepo(s).insert(rule)
        await s.commit()
        return rule.id


async def wipe_rules() -> None:
    """清空规则表：保证用例可重复执行。"""
    async with session_scope() as s:
        repo = PermissionRepo(s)
        for rule in await repo.list_all():
            await repo.delete(rule.id)
        await s.commit()


@pytest.fixture
async def clean_rules(engine_fixture: None) -> None:
    """清空规则表。"""
    await wipe_rules()


async def test_preset_strict_asks_read(clean_rules: None) -> None:
    """严格档：只读工具也询问。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    gate = PermissionGate()
    tool = make_registry().get("fs_read")
    assert tool is not None
    decision = await gate.decide(tool, "D:\\tmp", {"path": "x.txt"}, "strict")
    assert decision.verdict == "ask"


async def test_preset_loose_allows_write(clean_rules: None) -> None:
    """宽松档：写工具（非覆盖）放行。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    gate = PermissionGate()
    tool = make_registry().get("fs_write")
    assert tool is not None
    decision = await gate.decide(tool, "D:\\tmp", {"path": "new.txt", "content": "x"}, "loose")
    assert decision.verdict == "allow"


async def test_high_risk_always_asks_even_loose(clean_rules: None) -> None:
    """高危恒询问：宽松档下删除也问。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    gate = PermissionGate()
    tool = make_registry().get("fs_delete")
    assert tool is not None
    decision = await gate.decide(tool, "D:\\tmp", {"path": "x.txt"}, "loose")
    assert decision.verdict == "ask"


async def test_deny_rule_wins(clean_rules: None) -> None:
    """显式 deny 规则优先于预设。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    await add_rule("fs_write", "deny")
    gate = PermissionGate()
    tool = make_registry().get("fs_write")
    assert tool is not None
    decision = await gate.decide(tool, "D:\\tmp", {"path": "new.txt", "content": "x"}, "loose")
    assert decision.verdict == "deny"


async def test_most_specific_rule_wins(clean_rules: None) -> None:
    """规则最具体优先：全局通配 allow 不如目录级工具名 ask。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    await add_rule("*", "allow", level="global", work_dir="D:\\other")
    await add_rule("fs_write", "ask")
    gate = PermissionGate()
    tool = make_registry().get("fs_write")
    assert tool is not None
    decision = await gate.decide(tool, "D:\\tmp", {"path": "new.txt", "content": "x"}, "loose")
    assert decision.verdict == "ask"


async def test_ask_pause_and_resume_allowed_once(
    fake_server: tuple[Settings, list[dict[str, object]]], clean_rules: None, tmp_path: Path
) -> None:
    """询问停车 → allowed-once 恢复：permission/decided 成对、工具真执行。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    settings, _ = fake_server
    work_dir = str(tmp_path)
    gate = PermissionGate()
    runner = TurnRunner(
        small_settings(settings, "standard"),
        LlmGateway(settings),
        HookRegistry(),
        FakeEmbedder(),
        make_registry(),
        gate,
    )
    sid = await create_session(work_dir, "审批恢复", "standard")
    events = [event async for event in runner.run(sid, "tool_write")]
    required = [e for e in events if e.type == "permission.required"][0]
    request_id = str(required.payload["request_id"])
    assert events[-1].type == "turn.end"
    assert events[-1].payload.get("status") == "pending_stop"
    assert not (tmp_path / "out.txt").exists()
    resumed: list[object] = []
    async for event in runner.resume(sid, request_id, "allowed-once"):
        resumed.append(event)
        if event.type == "tool.result":
            break
    assert resumed[0].type == "permission/decided"
    assert any(e.type == "tool.result" and "已写入" in str(e.payload.get("result")) for e in resumed)
    assert (tmp_path / "out.txt").read_text(encoding="utf-8") == "可乐写入"


async def test_resume_rejected(
    fake_server: tuple[Settings, list[dict[str, object]]], clean_rules: None, tmp_path: Path
) -> None:
    """裁决拒绝：落拒绝结果，不执行。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    settings, _ = fake_server
    work_dir = str(tmp_path)
    gate = PermissionGate()
    runner = TurnRunner(
        small_settings(settings, "standard"),
        LlmGateway(settings),
        HookRegistry(),
        FakeEmbedder(),
        make_registry(),
        gate,
    )
    sid = await create_session(work_dir, "裁决拒绝", "standard")
    events = [event async for event in runner.run(sid, "tool_write")]
    request_id = str([e for e in events if e.type == "permission.required"][0].payload["request_id"])
    resumed: list[object] = []
    async for event in runner.resume(sid, request_id, "rejected"):
        resumed.append(event)
        if event.type == "tool.result":
            break
    assert any(e.type == "tool.result" and "用户拒绝" in str(e.payload.get("error")) for e in resumed)
    assert not (tmp_path / "out.txt").exists()


async def test_allowed_always_persists_rule(
    fake_server: tuple[Settings, list[dict[str, object]]], clean_rules: None, tmp_path: Path
) -> None:
    """始终允许：落目录级规则，此后不再询问。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    settings, _ = fake_server
    work_dir = str(tmp_path)
    gate = PermissionGate()
    runner = TurnRunner(
        small_settings(settings, "standard"),
        LlmGateway(settings),
        HookRegistry(),
        FakeEmbedder(),
        make_registry(),
        gate,
    )
    sid = await create_session(work_dir, "始终允许", "standard")
    events = [event async for event in runner.run(sid, "tool_write")]
    request_id = str([e for e in events if e.type == "permission.required"][0].payload["request_id"])
    async for event in runner.resume(sid, request_id, "allowed-always"):
        if event.type == "tool.result":
            break
    assert (tmp_path / "out.txt").exists()
    tool = make_registry().get("fs_write")
    assert tool is not None
    decision = await gate.decide(tool, work_dir, {"path": "again.txt", "content": "x"}, "standard")
    assert decision.verdict == "allow"


async def test_resume_unknown_request(
    fake_server: tuple[Settings, list[dict[str, object]]], clean_rules: None, tmp_path: Path
) -> None:
    """不存在的审批请求：报 60002。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    settings, _ = fake_server
    work_dir = str(tmp_path)
    runner = TurnRunner(
        small_settings(settings, "standard"),
        LlmGateway(settings),
        HookRegistry(),
        FakeEmbedder(),
        make_registry(),
        PermissionGate(),
    )
    sid = await create_session(work_dir, "未知审批", "standard")
    with pytest.raises(ApiError) as exc_info:
        async for _event in runner.resume(sid, "no-such-id", "allowed-once"):
            pass
    assert exc_info.value.error.code == 60002
