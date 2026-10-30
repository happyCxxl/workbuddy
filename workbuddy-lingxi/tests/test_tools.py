"""文件系统工具：直测执行与路径安全，以及循环接线的读/写/删裁决。"""

from pathlib import Path

import pytest
from conftest import FakeEmbedder, db_up

from lingxi.app.config import Settings
from lingxi.contract import ApiError
from lingxi.core.hooks import HookRegistry
from lingxi.core.log import replay_events
from lingxi.core.loop import TurnRunner
from lingxi.domain.llm.gateway import LlmGateway
from lingxi.domain.permission.gate import PermissionGate
from lingxi.domain.session.service import create_session
from lingxi.domain.tools.fs import register_fs_tools
from lingxi.domain.tools.registry import RISK_READ, RISK_WRITE, SessionToolState, ToolContext, ToolRegistry


def make_registry() -> ToolRegistry:
    """标准工具注册表。"""
    registry = ToolRegistry()
    register_fs_tools(registry)
    return registry


async def test_fs_read_write_list(tmp_path: Path) -> None:
    """读/写/列直测：限定工作目录内正常工作；读带行号。"""
    work_dir = str(tmp_path)
    (tmp_path / "hello.txt").write_text("你好，可乐", encoding="utf-8")
    registry = make_registry()
    read = registry.get("fs_read")
    assert read is not None and read.risk == RISK_READ
    output = await read.handler({"path": "hello.txt"}, ToolContext(work_dir))
    assert "1| 你好，可乐" in output
    write = registry.get("fs_write")
    assert write is not None and write.risk == RISK_WRITE
    output = await write.handler({"path": "out.txt", "content": "可乐写入"}, ToolContext(work_dir))
    assert "已写入" in output
    assert (tmp_path / "out.txt").read_text(encoding="utf-8") == "可乐写入"
    list_tool = registry.get("fs_list")
    assert list_tool is not None
    listing = await list_tool.handler({"path": "."}, ToolContext(work_dir))
    assert "hello.txt" in listing
    assert "out.txt" in listing


async def test_fs_read_pagination_and_line_numbers(tmp_path: Path) -> None:
    """分段读：offset/limit 生效，末尾给出继续读的 offset 提示。"""
    work_dir = str(tmp_path)
    (tmp_path / "lines.txt").write_text("a\nb\nc\nd\ne", encoding="utf-8")
    registry = make_registry()
    read = registry.get("fs_read")
    assert read is not None
    output = await read.handler({"path": "lines.txt", "offset": 2, "limit": 2}, ToolContext(work_dir))
    assert "2| b" in output
    assert "3| c" in output
    assert "1| a" not in output
    assert "文件共 5 行" in output
    assert "offset=4" in output


async def test_fs_edit_requires_read_and_unique_match(tmp_path: Path) -> None:
    """编辑：未读先拒；替换成功；old_string 不唯一时报错并可 replace_all。"""
    work_dir = str(tmp_path)
    target = tmp_path / "code.py"
    target.write_text("x = 1\nprint(x)\n", encoding="utf-8")
    state = SessionToolState()
    registry = make_registry()
    edit = registry.get("fs_edit")
    read = registry.get("fs_read")
    assert edit is not None and read is not None

    with pytest.raises(PermissionError, match="fs_read"):
        await edit.handler(
            {"path": "code.py", "old_string": "x = 1", "new_string": "x = 2"}, ToolContext(work_dir, state)
        )

    await read.handler({"path": "code.py"}, ToolContext(work_dir, state))
    output = await edit.handler(
        {"path": "code.py", "old_string": "x = 1", "new_string": "x = 2"}, ToolContext(work_dir, state)
    )
    assert "已替换 1 处" in output
    assert target.read_text(encoding="utf-8").startswith("x = 2")

    target.write_text("aa\nbb\naa\n", encoding="utf-8")
    await read.handler({"path": "code.py"}, ToolContext(work_dir, state))
    with pytest.raises(ValueError, match="不唯一"):
        await edit.handler({"path": "code.py", "old_string": "aa", "new_string": "cc"}, ToolContext(work_dir, state))
    output = await edit.handler(
        {"path": "code.py", "old_string": "aa", "new_string": "cc", "replace_all": True},
        ToolContext(work_dir, state),
    )
    assert "已替换 2 处" in output
    assert target.read_text(encoding="utf-8").count("cc") == 2


async def test_fs_write_requires_read_before_overwrite(tmp_path: Path) -> None:
    """覆盖已有文件前必须先读；新建文件不需要。"""
    work_dir = str(tmp_path)
    (tmp_path / "exists.txt").write_text("old", encoding="utf-8")
    state = SessionToolState()
    registry = make_registry()
    write = registry.get("fs_write")
    read = registry.get("fs_read")
    assert write is not None and read is not None

    with pytest.raises(PermissionError, match="fs_read"):
        await write.handler({"path": "exists.txt", "content": "new"}, ToolContext(work_dir, state))
    await read.handler({"path": "exists.txt"}, ToolContext(work_dir, state))
    await write.handler({"path": "exists.txt", "content": "new"}, ToolContext(work_dir, state))
    assert (tmp_path / "exists.txt").read_text(encoding="utf-8") == "new"

    output = await write.handler({"path": "fresh.txt", "content": "ok"}, ToolContext(work_dir, state))
    assert "已写入" in output


async def test_fs_glob_finds_files(tmp_path: Path) -> None:
    """glob：按模式返回相对路径；无匹配时给可执行提示。"""
    (tmp_path / "pkg").mkdir()
    (tmp_path / "pkg" / "a.py").write_text("", encoding="utf-8")
    (tmp_path / "pkg" / "b.txt").write_text("", encoding="utf-8")
    registry = make_registry()
    glob_tool = registry.get("fs_glob")
    assert glob_tool is not None
    output = await glob_tool.handler({"pattern": "**/*.py"}, ToolContext(str(tmp_path)))
    assert "pkg/a.py" in output
    assert "b.txt" not in output
    missing = await glob_tool.handler({"pattern": "**/*.rs"}, ToolContext(str(tmp_path)))
    assert "未匹配到文件" in missing


async def test_fs_search_regex_and_glob_filter(tmp_path: Path) -> None:
    """搜索：正则模式 + glob 过滤只命中目标文件。"""
    (tmp_path / "a.py").write_text("def foo():\n    pass\n", encoding="utf-8")
    (tmp_path / "b.txt").write_text("def foo():\n    pass\n", encoding="utf-8")
    registry = make_registry()
    search = registry.get("fs_search")
    assert search is not None
    output = await search.handler({"pattern": r"def\s+foo", "regex": True, "glob": "*.py"}, ToolContext(str(tmp_path)))
    assert "a.py:1:" in output
    assert "b.txt" not in output
    with pytest.raises(ValueError, match="正则"):
        await search.handler({"pattern": "([", "regex": True}, ToolContext(str(tmp_path)))


async def test_fs_read_outside_allowed_write_denied(tmp_path: Path) -> None:
    """读工作区外允许（越界读由权限层询问），写/改/删仍限工作区内。"""
    work_dir = str(tmp_path)
    secret = tmp_path.parent / "secret.txt"
    secret.write_text("机密", encoding="utf-8")
    registry = make_registry()
    read = registry.get("fs_read")
    assert read is not None
    assert "机密" in await read.handler({"path": "../secret.txt"}, ToolContext(work_dir))
    write = registry.get("fs_write")
    assert write is not None
    with pytest.raises(ValueError, match="工作区内"):
        await write.handler({"path": "../escape.txt", "content": "x"}, ToolContext(work_dir))


async def test_fs_delete_removes_file(tmp_path: Path) -> None:
    """删除直测：文件被移除（高危裁决在循环层）。"""
    work_dir = str(tmp_path)
    (tmp_path / "gone.txt").write_text("待删", encoding="utf-8")
    registry = make_registry()
    delete = registry.get("fs_delete")
    assert delete is not None
    assert "已删除" in await delete.handler({"path": "gone.txt"}, ToolContext(work_dir))
    assert not (tmp_path / "gone.txt").exists()


def test_tool_descriptions_and_field_docs() -> None:
    """ACI 口径：工具描述与每个参数字段都必须带说明（模型据此选工具/填参）。"""
    registry = make_registry()
    for name in ("fs_read", "fs_list", "fs_glob", "fs_search", "fs_write", "fs_edit", "fs_delete"):
        tool = registry.get(name)
        assert tool is not None
        assert len(tool.description) >= 15, f"{name} 描述过短"
        properties = tool.parameters["properties"]
        assert properties, f"{name} 缺少参数字段"
        for field, spec in properties.items():  # type: ignore[union-attr]
            assert "description" in spec, f"{name}.{field} 缺少字段说明"


async def test_fs_errors_are_actionable(tmp_path: Path) -> None:
    """报错要告诉模型下一步怎么做（读缺失提示 fs_list、目录提示 fs_list、写越界提示工作区内）。"""
    work_dir = str(tmp_path)
    (tmp_path / "dir").mkdir()
    registry = make_registry()
    read = registry.get("fs_read")
    assert read is not None
    with pytest.raises(FileNotFoundError, match="fs_list"):
        await read.handler({"path": "missing.txt"}, ToolContext(work_dir))
    with pytest.raises(IsADirectoryError, match="fs_list"):
        await read.handler({"path": "dir"}, ToolContext(work_dir))
    write = registry.get("fs_write")
    assert write is not None
    with pytest.raises(ValueError, match="工作区内"):
        await write.handler({"path": "../outside.txt", "content": "x"}, ToolContext(work_dir))


async def test_fs_write_creates_parent_dirs(tmp_path: Path) -> None:
    """写文件自动创建父目录（避免多一步手工建目录）。"""
    work_dir = str(tmp_path)
    registry = make_registry()
    write = registry.get("fs_write")
    assert write is not None
    output = await write.handler({"path": "src/deep/new.txt", "content": "x"}, ToolContext(work_dir))
    assert "已写入" in output
    assert (tmp_path / "src" / "deep" / "new.txt").read_text(encoding="utf-8") == "x"


async def test_fs_search_miss_reports_scan_count(tmp_path: Path) -> None:
    """搜索未命中时报告扫描数量，便于模型判断是范围问题还是真没有。"""
    work_dir = str(tmp_path)
    (tmp_path / "a.txt").write_text("hello", encoding="utf-8")
    registry = make_registry()
    search = registry.get("fs_search")
    assert search is not None
    output = await search.handler({"pattern": "找不到的词", "path": "."}, ToolContext(work_dir))
    assert "未命中" in output
    assert "已扫描" in output


async def test_loop_read_tool_end_to_end(
    fake_server: tuple[Settings, list[dict[str, object]]], engine_fixture: None, tmp_path: Path
) -> None:
    """循环接线：fs_read 真执行，tool.call/tool.result/tool.message 事件齐备。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    settings, _ = fake_server
    work_dir = str(tmp_path)
    (tmp_path / "hello.txt").write_text("你好，可乐", encoding="utf-8")
    small = Settings(
        server={},
        database={},
        tools={},
        memory=settings.memory.model_dump(),
        llm=settings.llm.model_dump(),
        permission={"preset": "standard"},
        loop={"input_token_budget": 100000, "context_budget_ratio": 0.8, "max_steps": 2},
    )
    runner = TurnRunner(small, LlmGateway(settings), HookRegistry(), FakeEmbedder(), make_registry(), PermissionGate())
    sid = await create_session(work_dir, "工具集成")
    with pytest.raises(ApiError):
        async for _event in runner.run(sid, "tool_read"):
            pass
    events = await replay_events(sid)
    types = [e.type for e in events]
    assert "tool.call" in types
    assert "tool.result" in types
    assert "tool/message" in types
    results = [e for e in events if e.type == "tool.result"]
    assert any("你好，可乐" in str(r.payload.get("result")) for r in results)


async def test_loop_write_asks_and_pauses(
    fake_server: tuple[Settings, list[dict[str, object]]],
    engine_fixture: None,
    clean_rules: None,
    tmp_path: Path,
) -> None:
    """写工具触发询问：permission.required + 回合 pending_stop 停车，未落盘。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    settings, _ = fake_server
    work_dir = str(tmp_path)
    small = Settings(
        server={},
        database={},
        tools={},
        memory=settings.memory.model_dump(),
        llm=settings.llm.model_dump(),
        permission={"preset": "standard"},
        loop={"input_token_budget": 100000, "context_budget_ratio": 0.8, "max_steps": 2},
    )
    runner = TurnRunner(small, LlmGateway(settings), HookRegistry(), FakeEmbedder(), make_registry(), PermissionGate())
    sid = await create_session(work_dir, "写工具询问")
    events = [event async for event in runner.run(sid, "tool_write")]
    assert any(e.type == "permission.required" for e in events)
    assert events[-1].type == "turn.end"
    assert events[-1].payload.get("status") == "pending_stop"
    assert not (tmp_path / "out.txt").exists()
