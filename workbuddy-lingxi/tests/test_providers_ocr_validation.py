"""OCR 结构化输出通道单测：手写解析、schema 校验结论与校验-重试兜底。"""

import pytest

from lingxi.db.models import Model, ModelVendor
from lingxi.model_gateway import providers
from lingxi.model_gateway.providers import ProviderError, _extract_fields, _extract_json, _merge_usage

SCHEMA: dict[str, object] = {
    "type": "object",
    "properties": {"text_lines": {"type": "array", "items": {"type": "object"}}},
    "required": ["text_lines"],
}


def _model() -> Model:
    return Model(vendor_id=1, model_key="ocr-a", display_name="ocr-a", capability="OCR")


def _vendor() -> ModelVendor:
    return ModelVendor(code="v", display_name="v", base_url="https://example.invalid", api_key="sk")


def test_extract_json_unwraps_fence_and_prose() -> None:
    assert _extract_json('```json\n{"a": 1}\n```') == '{"a": 1}'
    assert _extract_json('前面说明\n{"a": 1}\n后面') == '{"a": 1}'
    assert _extract_json('{"a": 1}') == '{"a": 1}'
    assert _extract_json("没有 JSON") is None


def test_extract_fields_accepts_valid_output() -> None:
    fields, error = _extract_fields('{"text_lines": []}', SCHEMA, "json_object")
    assert fields == {"text_lines": []}
    assert error is None


def test_extract_fields_reports_unparseable_output() -> None:
    _, error = _extract_fields("不是 JSON", SCHEMA, "json_object")
    assert "合法 JSON" in (error or "")


def test_extract_fields_reports_non_object_output() -> None:
    _, error = _extract_fields("[1, 2]", SCHEMA, "json_object")
    assert "必须是 JSON 对象" in (error or "")


def test_extract_fields_reports_schema_violation() -> None:
    _, error = _extract_fields('{"foo": 1}', SCHEMA, "json_object")
    assert "schema 校验失败" in (error or "")


def test_extract_fields_manual_layer_extracts_from_prose() -> None:
    fields, error = _extract_fields('结果如下：\n{"text_lines": [{"text": "行"}]}', SCHEMA, "manual")
    assert fields == {"text_lines": [{"text": "行"}]}
    assert error is None


async def test_schema_validated_text_retries_once_with_feedback(monkeypatch: pytest.MonkeyPatch) -> None:
    """首轮输出不合规：回喂错误与 schema 重试一次，重试合规即成功。"""
    calls: list[dict[str, object]] = []

    async def fake_completion(
        model: Model, vendor: ModelVendor, request: dict[str, object]
    ) -> tuple[str, dict[str, int]]:
        calls.append(request)
        if len(calls) == 1:
            return '{"foo": 1}', {"prompt": 10, "completion": 5, "total": 15}
        return '{"text_lines": [{"text": "行"}]}', {"prompt": 20, "completion": 6, "total": 26}

    monkeypatch.setattr(providers, "_completion", fake_completion)

    fields, usage = await providers._schema_validated_text(
        _model(), _vendor(), {"messages": [{"role": "user", "content": "识别图片"}]}, SCHEMA, "json_object"
    )

    assert fields == {"text_lines": [{"text": "行"}]}
    assert usage["total"] == 41
    assert len(calls) == 2
    content = calls[1]["messages"][0]["content"]
    assert "上次输出不符合要求" in content
    assert '"text_lines"' in content


async def test_schema_validated_text_raises_after_retry(monkeypatch: pytest.MonkeyPatch) -> None:
    """两轮都不合规：按 SCHEMA_VIOLATION 抛出（可换模型，调用方走降级）。"""

    async def fake_completion(
        model: Model, vendor: ModelVendor, request: dict[str, object]
    ) -> tuple[str, dict[str, int]]:
        return '{"foo": 1}', {"prompt": 1, "completion": 1, "total": 2}

    monkeypatch.setattr(providers, "_completion", fake_completion)

    with pytest.raises(ProviderError) as caught:
        await providers._schema_validated_text(
            _model(), _vendor(), {"messages": [{"role": "user", "content": "识别图片"}]}, SCHEMA, "manual"
        )
    assert caught.value.code == "SCHEMA_VIOLATION"
    assert caught.value.retryable is True


def test_merge_usage_sums_token_fields() -> None:
    merged = _merge_usage({"prompt": 10, "completion": 5, "total": 15}, {"prompt": 20, "completion": 6, "total": 26})
    assert merged == {"prompt": 30, "completion": 11, "total": 41}
