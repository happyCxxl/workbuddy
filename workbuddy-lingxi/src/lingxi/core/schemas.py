"""业务 schema 加载：schema 随包放在 lingxi/schemas/，文件名即 schema_key。"""

import json
import os
from functools import cache
from pathlib import Path
from typing import Any

import jsonschema

from lingxi.contract import ApiError, ErrorCode

_SCHEMAS_DIR = Path(__file__).resolve().parents[1] / "schemas"


def _schemas_dir() -> Path:
    """定位 schema 目录：默认包内 lingxi/schemas/，可用环境变量覆盖（测试/自定义）。"""
    override = os.environ.get("LINGXI_SCHEMAS_DIR")
    if override:
        base = Path(override).expanduser().resolve()
        if not base.is_dir():
            raise ApiError(ErrorCode.INVALID_ARGUMENT, f"schema 目录不存在：{override}")
        return base
    if not _SCHEMAS_DIR.is_dir():
        raise ApiError(ErrorCode.NOT_FOUND, f"包内 schema 目录缺失：{_SCHEMAS_DIR}")
    return _SCHEMAS_DIR


@cache
def load_schema(name: str) -> dict[str, Any]:
    """加载 schema 文件（含缓存）：文件名不带 .json；加载即校验 schema 自身合法。"""
    path = _schemas_dir() / f"{name}.json"
    if not path.is_file():
        raise ApiError(ErrorCode.NOT_FOUND, f"schema 文件不存在：{path}")
    try:
        schema = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise ApiError(ErrorCode.INVALID_ARGUMENT, f"schema 文件不是合法 JSON：{name}（{exc.msg}）") from exc
    try:
        jsonschema.Draft202012Validator.check_schema(schema)
    except jsonschema.SchemaError as exc:
        raise ApiError(ErrorCode.INVALID_ARGUMENT, f"schema 文件不合法：{name}（{exc.message}）") from exc
    return schema
