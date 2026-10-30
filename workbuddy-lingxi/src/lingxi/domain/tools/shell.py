"""shell 工具：受限沙箱执行命令；危险命令静态匹配（命中即恒询问，任何档位都不放行）。"""

import asyncio
import re

from pydantic import BaseModel, Field

from lingxi.app.config import Settings
from lingxi.domain.sandbox import WindowsSandbox
from lingxi.domain.tools.registry import RISK_WRITE, ToolContext, ToolDefinition, ToolRegistry, parse_int


class ShellArgs(BaseModel):
    """命令参数：command 必填；description 供界面展示用途。"""

    command: str = Field(
        description=(
            "要执行的完整 cmd 命令（Windows cmd.exe 语法），工作目录为会话工作目录；"
            "只有工作目录可写，读取不受限；危险命令会被询问。"
            "读文件用 fs_read、搜内容用 fs_search、找文件用 fs_glob、编辑用 fs_edit，不要用 type/findstr/dir 替代"
        )
    )
    description: str = Field(default="", description="给用户看的一句话用途说明（可选）")
    timeout_ms: int | None = Field(
        default=None,
        ge=1000,
        le=600_000,
        description="超时毫秒数（1s-10min）；不填用默认配置（120s）",
    )


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


_CREATE_NO_WINDOW = 0x08000000


async def _run_unsandboxed(command: str, cwd: str, timeout_ms: int, cap: int) -> str:
    """显式关闭沙箱（tools.sandbox=off）时直接执行：结果标注未沙箱，提醒用户边界。"""
    process = await asyncio.create_subprocess_shell(
        command,
        cwd=cwd,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.STDOUT,
        creationflags=_CREATE_NO_WINDOW,
    )
    try:
        stdout, _ = await asyncio.wait_for(process.communicate(), timeout_ms / 1000)
    except TimeoutError:
        process.kill()
        await process.wait()
        return f"[未沙箱] 命令超时（{timeout_ms} ms），已终止。"
    text = stdout.decode("utf-8", errors="replace")
    body = text if len(text) <= cap else f"{text[:cap]}\n…（输出已截断）"
    return f"[未沙箱] 退出码 {process.returncode}\n{body}"


def register_shell_tools(registry: ToolRegistry, settings: Settings) -> None:
    """注册 shell：写级别；危险命令命中参数级高危判定。"""

    async def _shell(args: dict[str, object], ctx: ToolContext) -> str:
        command = str(args.get("command") or "").strip()
        if not command:
            raise ValueError("命令不能为空（command 需为一条完整 cmd 命令）")
        default_ms = int(settings.tools.shell_timeout_seconds * 1000)
        timeout_ms = parse_int(args.get("timeout_ms"), default_ms)
        if settings.tools.sandbox == "off" or ctx.full_access:
            return await _run_unsandboxed(command, ctx.work_dir, timeout_ms, settings.tools.shell_output_cap_chars)
        sandbox = WindowsSandbox(
            ctx.work_dir,
            timeout_ms / 1000,
            settings.tools.shell_output_cap_chars,
        )
        result = await asyncio.to_thread(sandbox.run, command)
        return result.to_text()

    registry.register(
        ToolDefinition(
            name="shell",
            description=(
                "在 Windows 受限沙箱中执行 cmd 命令：只有当前工作目录可写（读取不受限）；"
                "输出最多 8KB；默认 120 秒超时（可用 timeout_ms 调整，上限 10 分钟）并终止进程树；"
                "危险命令（删盘/注册表/账户/服务/下载执行等）会先询问用户。"
                "读/搜/找/改文件优先用 fs_* 专用工具，不要用 type/findstr/dir 替代。"
            ),
            parameters=ShellArgs.model_json_schema(),
            risk=RISK_WRITE,
            handler=_shell,
            high_risk_args=lambda args: is_dangerous(str(args.get("command") or "")),
        )
    )
