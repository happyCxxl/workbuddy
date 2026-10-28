"""结构化通道测试：strict 成功 / 回退 json_object / 校验重试 / 超限失败。"""

import pytest
from pydantic import BaseModel

from lingxi.app.config import Settings
from lingxi.contract import ApiError
from lingxi.domain.llm.structured import StructuredOutput


class Sample(BaseModel):
    """测试目标模型。"""

    name: str
    score: int


def channel(settings: Settings) -> StructuredOutput:
    """构造结构化通道。"""
    return StructuredOutput(settings)


async def test_strict_success(fake_server: tuple[Settings, list[dict[str, object]]]) -> None:
    """strict 路径成功：直接返回校验通过的实例。"""
    settings, captured = fake_server
    result = await channel(settings).structured("ok_strict 请输出", Sample)
    assert result == Sample(name="可乐", score=1)
    assert any("input" in c for c in captured)


async def test_fallback_to_json_object(fake_server: tuple[Settings, list[dict[str, object]]]) -> None:
    """strict 不可用（400）→ 回退 json_object 并成功。"""
    settings, captured = fake_server
    result = await channel(settings).structured("no_strict 请输出", Sample)
    assert result == Sample(name="可乐", score=1)
    assert any(c.get("response_format") == {"type": "json_object"} for c in captured)


async def test_retry_succeeds_on_invalid_then_valid(fake_server: tuple[Settings, list[dict[str, object]]]) -> None:
    """首次输出非法 JSON，校验失败后重试成功。"""
    settings, captured = fake_server
    result = await channel(settings).structured("bad_once 请输出", Sample)
    assert result == Sample(name="可乐", score=1)
    json_calls = [c for c in captured if c.get("response_format") == {"type": "json_object"}]
    assert len(json_calls) >= 2


async def test_retry_exhausted_raises(fake_server: tuple[Settings, list[dict[str, object]]]) -> None:
    """恒非法输出：重试超限后按登记错误码失败。"""
    settings, _ = fake_server
    with pytest.raises(ApiError) as exc_info:
        await channel(settings).structured("always_bad 请输出", Sample)
    assert exc_info.value.error.code == 60101
