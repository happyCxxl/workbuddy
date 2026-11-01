"""一次性导入 Java wb_model 到 Python 模型能力层。

用法：cd workbuddy-lingxi && uv run python scripts/import_java_models.py
读取 docker 容器 workbuddy-mysql 的 workbuddy.wb_model，按能力拆行写入 model_vendor / model。
"""

import asyncio
import os
import re
import subprocess
from decimal import Decimal
from pathlib import Path

from sqlalchemy import select

from lingxi.app.config import Settings
from lingxi.db.engine import dispose_engine, init_engine, session_scope
from lingxi.db.models import Model, ModelVendor

_VENDOR_DEFAULTS = {
    "deepseek": ("DeepSeek", "https://api.deepseek.com", "DEEPSEEK_API_KEY"),
    "dashscope": ("阿里百炼", "https://dashscope.aliyuncs.com/compatible-mode/v1", "DASHSCOPE_API_KEY"),
}


def _read_java_models() -> list[list[str]]:
    """从 MySQL 读取未删除的模型行，返回制表符分隔的字段。"""
    sql = (
        "SELECT vendor_code,model_key,capabilities,thinking_enabled,input_limit,output_limit,"
        "context_window,price_in_per_m,price_out_per_m,currency,rpm_limit,tpm_limit,status "
        "FROM wb_model WHERE del_flag='0'"
    )
    result = subprocess.run(
        [
            "docker",
            "exec",
            "workbuddy-mysql",
            "mysql",
            "-uroot",
            "-proot123",
            "-D",
            "workbuddy",
            "--batch",
            "--skip-column-names",
            "-e",
            sql,
        ],
        capture_output=True,
        text=True,
        check=True,
    )
    return [line.split("\t") for line in result.stdout.splitlines() if line.strip()]


def _local_keys() -> dict[str, str]:
    """读取 Java 本地配置里的密钥（application-local.yml），环境变量优先。"""
    path = (
        Path(__file__).resolve().parents[2] / "workbuddy-server/workbuddy-biz/src/main/resources/application-local.yml"
    )
    keys: dict[str, str] = {}
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            match = re.match(r"\s*([A-Z0-9_]+):\s*\"([^\"]*)\"", line)
            if match:
                keys[match.group(1)] = match.group(2)
    for name in list(keys):
        if os.environ.get(name):
            keys[name] = os.environ[name]
    return keys


async def _import(rows: list[list[str]]) -> None:
    async with session_scope() as s:
        keys = _local_keys()
        vendors: dict[str, ModelVendor] = {}
        for vendor_code, display_name, base_url, env_name in _vendor_defaults(rows):
            vendor = await s.scalar(select(ModelVendor).where(ModelVendor.code == vendor_code))
            api_key = keys.get(env_name) or None
            if vendor is None:
                vendor = ModelVendor(
                    code=vendor_code,
                    display_name=display_name,
                    base_url=base_url,
                    api_key=api_key,
                )
                s.add(vendor)
                await s.flush()
            elif api_key:
                vendor.api_key = api_key
            vendors[vendor_code] = vendor
        for row in rows:
            (
                vendor_code,
                model_key,
                capabilities,
                thinking,
                _input,
                _output,
                context,
                price_in,
                price_out,
                currency,
                rpm,
                tpm,
                status,
            ) = row
            vendor = vendors[vendor_code]
            for capability in [item.strip() for item in capabilities.split(",") if item.strip()]:
                existing = await s.scalar(
                    select(Model).where(
                        Model.vendor_id == vendor.id,
                        Model.model_key == model_key,
                        Model.capability == capability,
                        Model.deleted_at.is_(None),
                    )
                )
                features: dict[str, object] = {}
                if capability == "CHAT":
                    features = {
                        "stream": True,
                        "tools": True,
                        "response_format": True,
                        "reasoning": thinking == "1",
                    }
                elif capability == "OCR":
                    features = {"structured_output": True}
                if existing is None:
                    existing = Model(
                        vendor_id=vendor.id,
                        model_key=model_key,
                        display_name=model_key,
                        capability=capability,
                        context_window=int(context) if context else None,
                        batch_limit=32,
                        rpm_limit=int(rpm) if rpm else None,
                        tpm_limit=int(tpm) if tpm else None,
                        price_in=Decimal(price_in) if price_in else None,
                        price_out=Decimal(price_out) if price_out else None,
                        currency=currency or "CNY",
                        features=features,
                        enabled=status == "ENABLED",
                    )
                    s.add(existing)
                else:
                    existing.display_name = model_key
                    existing.context_window = int(context) if context else None
                    existing.rpm_limit = int(rpm) if rpm else None
                    existing.tpm_limit = int(tpm) if tpm else None
                    existing.price_in = Decimal(price_in) if price_in else None
                    existing.price_out = Decimal(price_out) if price_out else None
                    existing.currency = currency or "CNY"
                    existing.features = features
                    existing.enabled = status == "ENABLED"
        await s.commit()


def _vendor_defaults(rows: list[list[str]]) -> list[tuple[str, str, str, str]]:
    """按数据里出现的厂商生成默认配置，未知厂商用占位地址。"""
    result: list[tuple[str, str, str, str]] = []
    for code in {row[0] for row in rows}:
        display, base_url, env = _VENDOR_DEFAULTS.get(
            code, (code, f"https://{code}.example.invalid/v1", f"{code.upper()}_API_KEY")
        )
        result.append((code, display, base_url, env))
    return result


async def _main() -> None:
    """初始化引擎、导入并释放。"""
    rows = _read_java_models()
    init_engine(Settings().database.url)
    try:
        await _import(rows)
    finally:
        await dispose_engine()
    print(f"imported {len(rows)} rows")


def main() -> None:
    """读取并导入；重复执行会更新已有记录。"""
    asyncio.run(_main())


if __name__ == "__main__":
    main()
