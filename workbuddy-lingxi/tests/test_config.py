"""配置加载：主文件 / 本地文件 / 初始化参数三级来源。"""

from lingxi.app.config import Settings


def test_config_toml_loads_classified_defaults() -> None:
    """主配置文件按域加载，模型名为 DeepSeek 现行命名。"""
    settings = Settings(**{})
    assert settings.server.port == 8000
    assert settings.loop.max_steps == 25
    assert settings.tools.shell_timeout_seconds == 120
    assert settings.llm.chat_model == "deepseek-flash"
    assert settings.llm.reasoner_model == "deepseek-v4-pro"


def test_init_kwargs_win_over_files() -> None:
    """初始化参数优先级最高：测试可任意覆盖。"""
    settings = Settings(llm={"chat_model": "override"})
    assert settings.llm.chat_model == "override"
    assert settings.llm.base_url == "https://api.deepseek.com"
