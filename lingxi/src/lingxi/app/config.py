"""应用配置：连接串与各类阈值集中在此，环境变量优先。"""

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """运行时配置：`LINGXI_` 前缀环境变量优先，缺省值用于本地开发。"""

    model_config = SettingsConfigDict(env_file=".env", env_prefix="LINGXI_")

    host: str = "127.0.0.1"
    port: int = 8000
    database_url: str = "postgresql+asyncpg://lingxi:lingxi@localhost:5432/lingxi"
    # 循环与预算闸门（主文档 §2 会话）
    max_steps: int = 25
    context_budget_ratio: float = 0.8
    # 工具执行（主文档 §6 能力）
    shell_timeout_seconds: int = 120
    shell_output_cap_chars: int = 8000
    web_fetch_output_cap_chars: int = 200_000
    web_timeout_seconds: int = 30
    # 模型出口（密钥经环境变量注入，不落代码）
    deepseek_api_key: str = ""
    deepseek_base_url: str = "https://api.deepseek.com"
