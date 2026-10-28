"""命令沙箱：只在 Windows 提供受限令牌实现（领域层出口）。"""

from lingxi.domain.sandbox.windows import SandboxError, SandboxResult, WindowsSandbox

__all__ = ["SandboxError", "SandboxResult", "WindowsSandbox"]
