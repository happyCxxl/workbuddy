"""业务 schema 加载：随包分发、加载即校验。"""

import pytest

from lingxi.contract import ApiError, ErrorCode
from lingxi.core.schemas import load_schema


def test_load_schema_returns_ocr_lines() -> None:
    schema = load_schema("ocr_lines")
    assert schema["type"] == "object"
    assert "text_lines" in schema["properties"]


def test_load_schema_missing_file_raises_not_found() -> None:
    with pytest.raises(ApiError) as caught:
        load_schema("no_such_schema")
    assert caught.value.error == ErrorCode.NOT_FOUND


def test_load_schema_rejects_invalid_schema(tmp_path, monkeypatch) -> None:
    """schema 文件本身不合法：加载即报错，不让坏 schema 流到运行时。"""
    (tmp_path / "bad.json").write_text('{"type": "banana"}', encoding="utf-8")
    monkeypatch.setenv("LINGXI_SCHEMAS_DIR", str(tmp_path))
    load_schema.cache_clear()
    try:
        with pytest.raises(ApiError) as caught:
            load_schema("bad")
        assert caught.value.error == ErrorCode.INVALID_ARGUMENT
    finally:
        load_schema.cache_clear()
