"""Web 工具：搜索/抓取解析、截断与失败语义（MockTransport，禁真实联网）。"""

import json
from collections.abc import Awaitable, Callable

import httpx2
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
from lingxi.domain.tools.registry import RISK_READ, ToolContext, ToolDefinition, ToolRegistry
from lingxi.domain.tools.web import register_web_tools

Handler = Callable[[httpx2.Request], Awaitable[httpx2.Response]]


def web_settings(fetch_cap: int = 200000, search_count: int = 3) -> Settings:
    """以配置文件的其余分节为底，覆盖 web 相关配置。"""
    return Settings(
        tools={
            "shell_timeout_seconds": 120,
            "shell_output_cap_chars": 8000,
            "web_fetch_output_cap_chars": fetch_cap,
            "web_timeout_seconds": 30,
            "web_search_count": search_count,
        }
    )


def make_client(handler: Handler) -> httpx2.AsyncClient:
    """构造带 MockTransport 的客户端：不出网。"""
    return httpx2.AsyncClient(transport=httpx2.MockTransport(handler))


async def test_search_formats_results_and_uses_configured_count() -> None:
    """搜索：结果格式化为序号/标题/链接/摘要；count 取配置且 need_content=false。"""
    captured: list[dict[str, object]] = []

    async def handler(request: httpx2.Request) -> httpx2.Response:
        captured.append(json.loads(request.content))
        payload = {
            "results": [
                {"title": "第一条", "url": "https://a.example", "summary": "摘要甲", "snippet": "短"},
                {"title": "第二条", "url": "https://b.example", "summary": "摘要乙", "snippet": "短"},
            ]
        }
        return httpx2.Response(200, json=payload)

    settings = web_settings(search_count=3)
    registry = ToolRegistry()
    register_web_tools(registry, settings, make_client(handler))
    tool = registry.get("web_search")
    assert tool is not None and tool.risk == RISK_READ
    output = await tool.handler({"query": "可乐"}, ToolContext("D:\\tmp"))
    assert "[1] 第一条" in output
    assert "https://a.example" in output
    assert "摘要甲" in output
    assert captured[0]["count"] == 3
    assert captured[0]["need_content"] is False


async def test_fetch_truncates_and_titles() -> None:
    """抓取：标题拼接正文，超上限截断并标注。"""

    async def handler(request: httpx2.Request) -> httpx2.Response:
        del request
        return httpx2.Response(200, json={"title": "页面标题", "content": "正文" * 100})

    settings = web_settings(fetch_cap=60)
    registry = ToolRegistry()
    register_web_tools(registry, settings, make_client(handler))
    tool = registry.get("web_fetch")
    assert tool is not None
    output = await tool.handler({"url": "https://example.com"}, ToolContext("D:\\tmp"))
    assert output.startswith("页面标题")
    assert "已截断" in output
    assert len(output) < 200


async def test_web_tool_descriptions_are_documented() -> None:
    """ACI：web 两个工具的说明与非空参数都要有描述（否则模型不知道何时用/怎么填）。"""

    async def handler(request: httpx2.Request) -> httpx2.Response:
        del request
        return httpx2.Response(200, json={"results": [], "title": "", "content": ""})

    registry = ToolRegistry()
    register_web_tools(registry, web_settings(), make_client(handler))
    for name in ("web_search", "web_fetch"):
        tool = registry.get(name)
        assert tool is not None
        assert len(tool.description) >= 20
        for field, spec in tool.parameters["properties"].items():  # type: ignore[union-attr]
            assert "description" in spec, f"{name}.{field} 缺少字段说明"


async def test_fetch_rejects_non_http_url() -> None:
    """抓取：非 http(s) URL 直接拒绝。"""

    async def handler(request: httpx2.Request) -> httpx2.Response:
        del request
        return httpx2.Response(500)

    registry = ToolRegistry()
    register_web_tools(registry, web_settings(), make_client(handler))
    tool = registry.get("web_fetch")
    assert tool is not None
    with pytest.raises(ValueError, match="http"):
        await tool.handler({"url": "file:///etc/passwd"}, ToolContext("D:\\tmp"))


async def test_web_tool_flows_back_into_loop(
    fake_server: tuple[Settings, list[dict[str, object]]], engine_fixture: None, tmp_path
) -> None:
    """循环接线：web 工具结果进入 tool.result（用假 handler 免联网）。"""
    if not await db_up():
        pytest.skip("数据库未启动：先 docker compose up -d postgres")
    settings, _ = fake_server

    async def fake_search(args: dict[str, object], ctx: ToolContext) -> str:
        del args, ctx
        return "搜索结果：可乐是一种 AI 助理"

    registry = ToolRegistry()
    register_fs_tools(registry)
    registry.register(
        ToolDefinition(
            name="web_search",
            description="测试用伪搜索",
            parameters={"type": "object", "properties": {"query": {"type": "string"}}},
            risk=RISK_READ,
            handler=fake_search,
        )
    )
    runner = TurnRunner(settings, LlmGateway(settings), HookRegistry(), FakeEmbedder(), registry, PermissionGate())
    sid = await create_session(str(tmp_path), "web 集成")
    with pytest.raises(ApiError):
        async for _event in runner.run(sid, "tool_web"):
            pass
    events = await replay_events(sid)
    results = [e for e in events if e.type == "tool.result"]
    assert any("可乐是一种 AI 助理" in str(r.payload.get("result")) for r in results)
