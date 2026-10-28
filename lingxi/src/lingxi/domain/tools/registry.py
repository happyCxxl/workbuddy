"""工具注册表：schema 白名单唯一来源；工具名 → 规格、风险级与执行函数。"""

from collections.abc import Awaitable, Callable
from dataclasses import dataclass

from lingxi.domain.ports import ToolSpec

RISK_READ = "read"
RISK_WRITE = "write"
RISK_HIGH = "high"


@dataclass
class ToolContext:
    """工具执行上下文：会话工作目录。"""

    work_dir: str


Handler = Callable[[dict[str, object], ToolContext], Awaitable[str]]


@dataclass
class ToolDefinition:
    """工具定义：名称、说明、参数 schema、风险级与执行函数。"""

    name: str
    description: str
    parameters: dict[str, object]
    risk: str
    handler: Handler


class ToolRegistry:
    """工具注册表：模型可见的 tools 清单只从这里生成（schema 白名单）。"""

    def __init__(self) -> None:
        self._tools: dict[str, ToolDefinition] = {}

    def register(self, tool: ToolDefinition) -> None:
        """注册工具：同名覆盖。"""
        self._tools[tool.name] = tool

    def get(self, name: str) -> ToolDefinition | None:
        """按名取定义。"""
        return self._tools.get(name)

    def specs(self) -> list[ToolSpec]:
        """生成模型侧工具清单。"""
        return [
            ToolSpec(name=definition.name, description=definition.description, parameters=definition.parameters)
            for definition in self._tools.values()
        ]
