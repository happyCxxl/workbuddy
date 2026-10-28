"""Web 工具：搜索与抓取（DeepSeek 官方 Search / Fetch API，返回结构化结果与 Markdown）。"""

from typing import Any, cast

import httpx2
from pydantic import BaseModel

from lingxi.app.config import Settings
from lingxi.domain.tools.registry import RISK_READ, ToolContext, ToolDefinition, ToolRegistry

SEARCH_RESULT_CHARS = 20_000


class SearchArgs(BaseModel):
    """搜索参数：query 必填，count 受服务端上限约束。"""

    query: str
    count: int = 5


class FetchArgs(BaseModel):
    """抓取参数：仅接受 HTTP/HTTPS URL。"""

    url: str


class WebClient:
    """Search / Fetch 客户端：与模型出口同一 base_url 与密钥，超时可配。"""

    def __init__(self, settings: Settings, client: "httpx2.AsyncClient | None" = None) -> None:
        self._base_url = settings.llm.base_url.rstrip("/")
        self._api_key = settings.llm.api_key
        self._timeout = settings.tools.web_timeout_seconds
        self._client = client

    async def _post(self, path: str, payload: dict[str, object]) -> dict[str, object]:
        """POST 请求：非 2xx 按错误上抛，由工具层转失败结果。"""
        headers = {"Authorization": f"Bearer {self._api_key}", "Content-Type": "application/json"}
        if self._client is not None:
            response = await self._client.post(
                f"{self._base_url}{path}", json=payload, headers=headers, timeout=self._timeout
            )
        else:
            async with httpx2.AsyncClient() as client:
                response = await client.post(
                    f"{self._base_url}{path}", json=payload, headers=headers, timeout=self._timeout
                )
        if response.status_code >= 300:
            raise RuntimeError(f"Web 接口失败（HTTP {response.status_code}）：{response.text[:200]}")
        return cast(dict[str, object], response.json())

    async def search(self, query: str, count: int) -> list[dict[str, object]]:
        """搜索：只取摘要不取正文（正文由 fetch 按需获取，省 token）。"""
        body = await self._post("/v1/tools/search", {"query": query, "count": count, "need_content": False})
        raw = body.get("results")
        if not isinstance(raw, list):
            return []
        items = cast(list[object], raw)
        return [cast(dict[str, object], item) for item in items]

    async def fetch(self, url: str) -> tuple[str, str]:
        """抓取：返回（标题，Markdown 正文）。"""
        body = await self._post("/v1/tools/fetch", {"url": url})
        return str(body.get("title") or ""), str(body.get("content") or "")


def _format_search(results: list[dict[str, object]]) -> str:
    """搜索结果 → 模型可读文本：序号、标题、链接、摘要。"""
    if not results:
        return "未找到相关结果。"
    lines: list[str] = []
    for index, result in enumerate(results, start=1):
        lines.append(f"[{index}] {result.get('title') or ''}")
        lines.append(f"链接：{result.get('url') or ''}")
        lines.append(f"摘要：{result.get('summary') or result.get('snippet') or ''}")
    text = "\n".join(lines)
    return text[:SEARCH_RESULT_CHARS]


def register_web_tools(registry: ToolRegistry, settings: Settings, client: "httpx2.AsyncClient | None" = None) -> None:
    """注册 web_search / web_fetch：均为只读工具。"""
    web = WebClient(settings, client)

    async def _search(args: dict[str, object], ctx: ToolContext) -> str:
        del ctx
        count = max(1, min(int(cast(Any, args.get("count")) or settings.tools.web_search_count), 50))
        query = str(args["query"])
        return _format_search(await web.search(query, count))

    async def _fetch(args: dict[str, object], ctx: ToolContext) -> str:
        del ctx
        url = str(args["url"])
        if not (url.startswith("http://") or url.startswith("https://")):
            raise ValueError(f"只支持 http/https URL：{url}")
        title, content = await web.fetch(url)
        cap = settings.tools.web_fetch_output_cap_chars
        body = content[:cap]
        if len(content) > cap:
            body += "\n…（已截断）"
        return f"{title}\n\n{body}" if title else body

    registry.register(
        ToolDefinition(
            name="web_search",
            description="联网搜索：返回若干结果的标题、链接与摘要（需要正文时用 web_fetch 抓取）",
            parameters=SearchArgs.model_json_schema(),
            risk=RISK_READ,
            handler=_search,
        )
    )
    registry.register(
        ToolDefinition(
            name="web_fetch",
            description="抓取网页并转为 Markdown（只读；输出有上限、超时可配）",
            parameters=FetchArgs.model_json_schema(),
            risk=RISK_READ,
            handler=_fetch,
        )
    )
