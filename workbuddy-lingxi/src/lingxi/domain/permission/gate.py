"""权限门：显式规则（最具体优先）→ 高危恒询问 → 三档预设兜底；审批挂起异常。"""

from dataclasses import dataclass
from pathlib import Path

from lingxi.db.engine import session_scope
from lingxi.db.models import PermissionRule
from lingxi.db.repo.permission_repo import PermissionRepo
from lingxi.domain.tools.registry import RISK_HIGH, RISK_READ, RISK_WRITE, ToolDefinition

PRESETS = ("read-only", "workspace-write", "full-access")


@dataclass
class Decision:
    """裁决结果：allow / ask / deny + 人读理由。"""

    verdict: str
    reason: str


class PendingApproval(Exception):
    """工具执行被挂起等待用户裁决：由回合层转为 pending_stop 停车。"""

    def __init__(self, request_id: str) -> None:
        super().__init__(request_id)
        self.request_id = request_id


def _specificity(rule: PermissionRule) -> int:
    """规则具体度：工具名具体 +2，操作域具体 +1。"""
    return (rule.tool != "*") * 2 + (rule.scope is not None) * 1


def _path_of(tool: ToolDefinition, args: dict[str, object]) -> str | None:
    """取工具的路径参数（越界/覆盖判定用）。"""
    for field in tool.path_fields:
        value = args.get(field)
        if isinstance(value, str):
            return value
    return None


def _escapes(work_dir: str, path: str) -> bool:
    """路径是否越出工作目录。"""
    base = Path(work_dir).resolve()
    target = (base / path).resolve()
    return not target.is_relative_to(base)


def _exists(work_dir: str, path: str) -> bool:
    """路径是否存在（覆盖判定）。"""
    base = Path(work_dir).resolve()
    return (base / path).resolve().exists()


class PermissionGate:
    """权限裁决：规则命中即定；否则高危恒询问；否则按会话档位兜底（无状态）。"""

    @staticmethod
    async def decide(tool: ToolDefinition, work_dir: str, args: dict[str, object], preset: str) -> Decision:
        """裁决顺序：显式规则 → 高危恒询问 → 会话预设（兼容旧档位名）。"""
        preset = {"loose": "workspace-write", "standard": "workspace-write", "strict": "read-only"}.get(preset, preset)
        async with session_scope() as s:
            rules = await PermissionRepo(s).list_matching(tool.name, work_dir)
        scoped = [r for r in rules if r.scope is None or r.scope == tool.risk or r.scope == "any"]
        best = max(scoped, key=lambda rule: (_specificity(rule), rule.id), default=None)
        if best is not None:
            return Decision(verdict=best.action, reason=f"规则命中（{best.action}）")
        if preset == "read-only" and tool.risk in (RISK_WRITE, RISK_HIGH):
            return Decision(verdict="deny", reason="仅可查看档：禁止写操作")
        if tool.risk == RISK_HIGH:
            return Decision(verdict="ask", reason="高危工具恒询问")
        if tool.high_risk_args is not None and tool.high_risk_args(args):
            return Decision(verdict="ask", reason="高危命令恒询问")
        path = _path_of(tool, args)
        if path is not None:
            if _escapes(work_dir, path):
                if tool.risk == RISK_READ:
                    return Decision(verdict="ask", reason="读取工作区外路径需确认")
                if preset != "full-access":
                    return Decision(verdict="deny", reason="写操作只能在工作区内")
            if tool.name == "fs_write" and _exists(work_dir, path):
                return Decision(verdict="ask", reason="覆盖已有文件需确认")
        if preset == "full-access":
            return Decision(verdict="allow", reason="完全权限档放行")
        if preset == "workspace-write":
            return Decision(verdict="allow", reason="工作区内修改档放行")
        if preset == "read-only":
            return Decision(verdict="allow", reason="仅可查看档放行只读")
        return Decision(verdict="deny", reason=f"未知权限档：{preset}")

    @staticmethod
    async def remember_allow(tool_name: str, work_dir: str) -> None:
        """“始终允许”落目录级规则。"""
        async with session_scope() as s:
            await PermissionRepo(s).insert(
                PermissionRule(tool=tool_name, scope=None, action="allow", level="dir", work_dir=work_dir)
            )
            await s.commit()
