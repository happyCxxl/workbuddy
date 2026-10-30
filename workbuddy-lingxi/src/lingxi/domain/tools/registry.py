"""工具注册表：schema 白名单唯一来源；工具名 → 规格、风险级与执行函数。"""

from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field

from lingxi.domain.ports import ToolSpec

RISK_READ = "read"
RISK_WRITE = "write"
RISK_HIGH = "high"


@dataclass
class SessionToolState:
    """会话级工具状态：记录已读文件，用于“读前写/改”门禁。"""

    read_files: set[str] = field(default_factory=lambda: set[str]())


def parse_int(value: object, default: int) -> int:
    """工具参数转整数：只接受 int 与数字字符串，其余回落默认值（模型偶发传错不崩）。"""
    if isinstance(value, bool):
        return int(value)
    if isinstance(value, int):
        return value
    if isinstance(value, str):
        try:
            return int(value)
        except ValueError:
            return default
    return default


@dataclass
class ToolContext:
    """工具执行上下文：会话工作目录、可选的会话级状态与完全权限标记（直接单测可省略）。"""

    work_dir: str
    state: SessionToolState | None = None
    full_access: bool = False


Handler = Callable[[dict[str, object], ToolContext], Awaitable[str]]


@dataclass
class ToolDefinition:
    """工具定义：名称、说明、参数 schema、风险级、路径参数与执行函数。"""

    name: str
    description: str
    parameters: dict[str, object]
    risk: str
    handler: Handler
    path_fields: tuple[str, ...] = ()
    # 参数级高危判定：命中即恒询问（例如 shell 的危险命令），优先于会话预设
    high_risk_args: Callable[[dict[str, object]], bool] | None = None


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
