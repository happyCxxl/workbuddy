"""shell 工具：受限沙箱执行命令；危险命令静态匹配（命中即恒询问，任何档位都不放行）。"""

import asyncio
import re

from pydantic import BaseModel

from lingxi.app.config import Settings
from lingxi.domain.sandbox import WindowsSandbox
from lingxi.domain.tools.registry import RISK_WRITE, ToolContext, ToolDefinition, ToolRegistry


class ShellArgs(BaseModel):
    """命令参数：command 必填；description 供界面展示用途。"""

    command: str
    description: str = ""


# 危险命令：系统级破坏、权限/账户/服务变更、下载后执行（命中即询问，不依赖档位）
DANGEROUS_PATTERNS: tuple[str, ...] = (
    r"\bformat(\.com)?\b",
    r"\bdiskpart\b",
    r"\bbcdedit\b",
    r"\bshutdown\b",
    r"\brestart-computer\b",
    r"\breg\s+(add|delete|load|save|copy|import|export)\b",
    r"\bnet\s+(user|localgroup|accounts)\b",
    r"\bsc\s+(delete|create|config|stop|start)\b",
    r"\btaskkill\b.*/f",
    r"\btakeown\b",
    r"\bicacls\b",
    r"\bcipher\s+/w\b",
    r"\bwmic\b",
    r"\bvssadmin\b",
    r"\b(powershell|pwsh)\b.*(-enc|-encodedcommand|iex|invoke-expression|downloadstring|bypass)",
    r"\b(curl|iwr|invoke-webrequest)\b.*\|\s*(cmd|powershell|pwsh)",
    r"\b(certutil|bitsadmin)\b.*(-urlcache|-transfer)",
    r"\bmshta\b",
    r"\bregsvr32\b",
    r"\brundll32\b",
    r"\bdel\b.*(/s|/f|/q)\b",
    r"\b(rd|rmdir)\b.*/s\b",
    r"\bremove-item\b.*-recurse\b",
    r"\bgit\s+push\b.*--force",
    r"\bgit\s+reset\b.*--hard",
)

_COMPILED = tuple(re.compile(pattern) for pattern in DANGEROUS_PATTERNS)


def is_dangerous(command: str) -> bool:
    """危险命令判定：统一小写并压缩空白后匹配（防大小写/多空格变体）。"""
    normalized = " ".join(command.lower().split())
    return any(pattern.search(normalized) is not None for pattern in _COMPILED)


def register_shell_tools(registry: ToolRegistry, settings: Settings) -> None:
    """注册 shell：写级别；危险命令命中参数级高危判定。"""

    async def _shell(args: dict[str, object], ctx: ToolContext) -> str:
        command = str(args.get("command") or "").strip()
        if not command:
            raise ValueError("命令不能为空")
        sandbox = WindowsSandbox(
            ctx.work_dir,
            settings.tools.shell_timeout_seconds,
            settings.tools.shell_output_cap_chars,
        )
        result = await asyncio.to_thread(sandbox.run, command)
        return result.to_text()

    registry.register(
        ToolDefinition(
            name="shell",
            description="在受限沙箱中执行命令：仅当前工作目录可写，输出截断、超时终止；危险命令会先询问",
            parameters=ShellArgs.model_json_schema(),
            risk=RISK_WRITE,
            handler=_shell,
            high_risk_args=lambda args: is_dangerous(str(args.get("command") or "")),
        )
    )
