"""配置加载：主文件 / 本地文件 / 初始化参数三级来源。"""

from lingxi.app.config import Settings


def test_config_toml_loads_classified_defaults(monkeypatch, tmp_path) -> None:
    """主配置文件按域加载，模型名为 DeepSeek 现行命名（屏蔽本机 config.local.toml 的覆盖）。"""
    monkeypatch.setattr("lingxi.app.config.LOCAL_CONFIG_FILE", str(tmp_path / "absent.toml"))
    settings = Settings(**{})
    assert settings.server.port == 8000
    assert settings.loop.max_steps == 25
    assert settings.tools.shell_timeout_seconds == 120
    assert settings.llm.chat_model == "deepseek-flash"
    assert settings.llm.reasoner_model


def test_init_kwargs_win_over_files() -> None:
    """初始化参数优先级最高：测试可任意覆盖。"""
    settings = Settings(llm={"chat_model": "override"})
    assert settings.llm.chat_model == "override"
    assert settings.llm.base_url == "https://api.deepseek.com"


def test_local_config_overrides_main(monkeypatch, tmp_path) -> None:
    """本地配置覆盖主配置：同名键优先，未写的键沿用主文件（嵌套表逐层合并）。"""
    local = tmp_path / "config.local.toml"
    local.write_text('[llm]\napi_key = "local-key"\n', encoding="utf-8")
    monkeypatch.setattr("lingxi.app.config.LOCAL_CONFIG_FILE", str(local))
    settings = Settings(**{})
    assert settings.llm.api_key == "local-key"
    assert settings.memory.embedding_model == "BAAI/bge-m3"
