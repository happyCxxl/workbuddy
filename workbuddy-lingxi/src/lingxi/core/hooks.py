"""同步钩子：四相位注册表与内置闸门；任一钩子抛错即停回合（fail-closed）。"""

from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from typing import cast

from lingxi.contract import ApiError, ErrorCode


def _empty_payload() -> dict[str, object]:
    """载荷默认工厂：保持空字典类型可推断。"""
    return {}


@dataclass
class TurnContext:
    """钩子上下文：会话与回合定位、相位动作与载荷。"""

    session_id: int
    turn_seq: int
    step: int
    action: str
    tool_name: str | None = None
    payload: dict[str, object] = field(default_factory=_empty_payload)


HookFunc = Callable[[TurnContext], Awaitable[None]]

PHASES = ("pre_turn", "pre_step", "pre_execute", "post_execute")


class HookRegistry:
    """四相位钩子注册表：按注册顺序执行，异常上抛（fail-closed）。"""

    def __init__(self) -> None:
        self._hooks: dict[str, list[HookFunc]] = {phase: [] for phase in PHASES}

    def register(self, phase: str, hook: HookFunc) -> None:
        """注册钩子：相位必须合法。"""
        if phase not in self._hooks:
            raise ValueError(f"未知钩子相位：{phase}")
        self._hooks[phase].append(hook)

    async def run(self, phase: str, ctx: TurnContext) -> None:
        """执行该相位全部钩子；任一异常上抛。"""
        for hook in self._hooks[phase]:
            await hook(ctx)


def make_budget_gate(input_token_budget: int, ratio: float) -> HookFunc:
    """预算闸门：估算超 80% 标记压缩；超上限硬停（fail-closed）。"""

    async def gate(ctx: TurnContext) -> None:
        estimate = cast(int, ctx.payload["estimate"])
        if estimate > input_token_budget:
            raise ApiError(ErrorCode.TURN_ABORTED, "输入 token 预算超限")
        if estimate > input_token_budget * ratio:
            ctx.payload["compact"] = True

    return gate


def make_max_steps_gate(max_steps: int) -> HookFunc:
    """步数闸门：step 超过上限即停（fail-closed）。"""

    async def gate(ctx: TurnContext) -> None:
        if ctx.step > max_steps:
            raise ApiError(ErrorCode.TURN_ABORTED, f"步数超限（{max_steps}）")

    return gate
