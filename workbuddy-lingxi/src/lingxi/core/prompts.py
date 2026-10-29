"""静态提示词加载：提示词随包放在 lingxi/prompts/，模板占位符用 {{name}} 显式替换。"""

import os
from functools import cache
from pathlib import Path

from lingxi.contract import ApiError, ErrorCode

PLACEHOLDER = "{{"
_PROMPTS_DIR = Path(__file__).resolve().parents[1] / "prompts"


def _prompts_dir() -> Path:
    """定位提示词目录：默认包内 lingxi/prompts/，可用环境变量覆盖（测试/自定义）。"""
    override = os.environ.get("LINGXI_PROMPTS_DIR")
    if override:
        base = Path(override).expanduser().resolve()
        if not base.is_dir():
            raise ApiError(ErrorCode.INVALID_ARGUMENT, f"提示词目录不存在：{override}")
        return base
    if not (_PROMPTS_DIR / "behavior.md").is_file():
        raise ApiError(ErrorCode.NOT_FOUND, f"包内提示词目录不完整：{_PROMPTS_DIR}")
    return _PROMPTS_DIR


@cache
def load_prompt(name: str) -> str:
    """读取提示词文件（含缓存）：文件名不带 .md。"""
    path = _prompts_dir() / f"{name}.md"
    if not path.is_file():
        raise ApiError(ErrorCode.NOT_FOUND, f"提示词文件不存在：{path}")
    return path.read_text(encoding="utf-8").rstrip() + "\n"


def render_prompt(template: str, **values: str) -> str:
    """渲染模板：替换 {{key}} 占位符；模板中残留未替换占位符直接报错，防止漏传参数。"""
    text = load_prompt(template)
    for key, value in values.items():
        text = text.replace("{{" + key + "}}", value)
    if PLACEHOLDER in text:
        missing = _missing_placeholders(text)
        raise ApiError(ErrorCode.INVALID_ARGUMENT, f"提示词模板 {template}.md 存在未替换占位符：{', '.join(missing)}")
    return text


def _missing_placeholders(text: str) -> list[str]:
    """提取残留占位符名称（去重、保序）。"""
    names: list[str] = []
    cursor = 0
    while True:
        start = text.find("{{", cursor)
        if start < 0:
            return names
        end = text.find("}}", start + 2)
        if end < 0:
            return names
        name = text[start + 2 : end].strip()
        if name and name not in names:
            names.append(name)
        cursor = end + 2
