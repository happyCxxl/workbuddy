"""应用配置：只定义结构，不含任何配置值。

取值优先级：初始化参数 > `LINGXI_` 前缀环境变量（嵌套用 `__` 分隔）> config.local.toml（本地，不入库）> config.toml（入库）。
全部值来自配置文件；敏感字段（数据库口令、模型密钥）只允许出现在 config.local.toml 或环境变量中。
"""

import os
import tomllib
from pathlib import Path
from typing import Literal, cast

from pydantic import BaseModel
from pydantic_settings import BaseSettings, PydanticBaseSettingsSource, SettingsConfigDict

_ROOT = Path(__file__).resolve().parents[3]
CONFIG_FILE = str(_ROOT / "config.toml")
LOCAL_CONFIG_FILE = str(_ROOT / "config.local.toml")


class ServerConfig(BaseModel):
    """服务监听。"""

    host: str
    port: int


class DatabaseConfig(BaseModel):
    """数据库连接。"""

    url: str


class LoopConfig(BaseModel):
    """循环与预算闸门。"""

    max_steps: int
    context_budget_ratio: float
    input_token_budget: int


class ToolsConfig(BaseModel):
    """工具执行。"""

    shell_timeout_seconds: int
    shell_output_cap_chars: int
    web_fetch_output_cap_chars: int
    web_timeout_seconds: int
    web_search_count: int
    sandbox: Literal["workspace-write", "off"] = "workspace-write"


class LlmConfig(BaseModel):
    """模型出口。"""

    provider: str
    base_url: str
    chat_model: str
    reasoner_model: str
    structured_max_retries: int
    failure_rate_threshold: float
    cooldown_seconds: int
    retry_budget: int
    api_key: str


class MemoryConfig(BaseModel):
    """记忆向量化：OpenAI 兼容 embedding API（与模型出口同款接入模式）。"""

    embedding_provider: str
    embedding_base_url: str
    embedding_model: str
    embedding_api_key: str


class PermissionConfig(BaseModel):
    """权限预设档位。"""

    preset: str


def _read_toml(path: str) -> dict[str, object]:
    """读取 TOML 配置；文件不存在返回空。"""
    if not os.path.exists(path):
        return {}
    with open(path, "rb") as file:
        return tomllib.load(file)


def _merge(base: dict[str, object], override: dict[str, object]) -> None:
    """递归合并配置：同名嵌套表逐层合并，标量与列表由后者整体覆盖。"""
    for key, value in override.items():
        current = base.get(key)
        if isinstance(value, dict) and isinstance(current, dict):
            _merge(cast(dict[str, object], current), cast(dict[str, object], value))
        else:
            base[key] = value


class TomlSource(PydanticBaseSettingsSource):
    """TOML 配置源：按文件顺序加载，后者覆盖前者；嵌套表逐层合并，本地可只写要覆盖的键。"""

    def __init__(self, settings_cls: type[BaseSettings], files: tuple[str, ...]) -> None:
        super().__init__(settings_cls)
        self._files = files

    def get_field_value(self, field: object, field_name: str) -> tuple[object, str, bool]:
        """单字段取值：本源按整体字典加载，字段级不命中。"""
        return None, field_name, False

    def __call__(self) -> dict[str, object]:
        """合并全部文件为一层嵌套字典。"""
        data: dict[str, object] = {}
        for file in self._files:
            _merge(data, _read_toml(file))
        return data


class Settings(BaseSettings):
    """运行时配置：环境变量 > 本地文件 > 主文件；无任何默认值。"""

    model_config = SettingsConfigDict(env_prefix="LINGXI_", env_nested_delimiter="__")

    server: ServerConfig
    database: DatabaseConfig
    loop: LoopConfig
    tools: ToolsConfig
    llm: LlmConfig
    memory: MemoryConfig
    permission: PermissionConfig

    @classmethod
    def settings_customise_sources(
        cls,
        settings_cls: type[BaseSettings],
        init_settings: PydanticBaseSettingsSource,
        env_settings: PydanticBaseSettingsSource,
        dotenv_settings: PydanticBaseSettingsSource,
        file_secret_settings: PydanticBaseSettingsSource,
    ) -> tuple[PydanticBaseSettingsSource, ...]:
        """注入 TOML 源：排在环境变量之后；主文件先加载，本地文件后加载以覆盖同名键。"""
        return (
            init_settings,
            env_settings,
            TomlSource(settings_cls, (CONFIG_FILE, LOCAL_CONFIG_FILE)),
            file_secret_settings,
        )
