"""静态提示词：目录统一管理、模板渲染与漏参报错。"""

import pytest

from lingxi.contract import ApiError
from lingxi.core.prompts import load_prompt, render_prompt


def test_all_static_prompts_load() -> None:
    """prompts/ 下所有提示词都能读取且非空。"""
    for name in (
        "behavior",
        "capability",
        "persona",
        "sub_session",
        "source_chat",
        "source_sub",
        "runtime",
        "memory",
    ):
        text = load_prompt(name)
        assert text.strip(), f"{name}.md 为空"
    assert "先读后改" in load_prompt("behavior")
    assert "应用注入" in load_prompt("source_chat")


def test_capability_template_renders_tools() -> None:
    """能力段模板：工具清单注入且不残留占位符。"""
    text = render_prompt("capability", tools="- fs_read：读取文件")
    assert "- fs_read：读取文件" in text
    assert "执行约定" in text
    assert "{{tools}}" not in text


def test_runtime_and_memory_templates_render() -> None:
    """运行时与记忆模板：占位符全部替换。"""
    runtime = render_prompt(
        "runtime", time="2026-01-01 00:00:00 CST", work_dir="D:\\work", platform="Windows", platform_code="win32"
    )
    assert "D:\\work" in runtime
    assert "Windows" in runtime
    memory = render_prompt("memory", items="1. （fact，2026-01-01）喜欢喝茶")
    assert "喜欢喝茶" in memory


def test_missing_template_parameter_raises() -> None:
    """漏传占位符直接报错，防止漏参悄悄上线。"""
    with pytest.raises(ApiError, match="未替换占位符"):
        render_prompt("capability")


def test_unknown_prompt_file_raises() -> None:
    """未登记的文件名报错。"""
    with pytest.raises(ApiError, match="不存在"):
        load_prompt("no_such_prompt")
