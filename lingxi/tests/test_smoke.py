"""骨架冒烟：包可导入、入口可启动。"""

import pytest

import lingxi
from lingxi import __main__


def test_import_and_version() -> None:
    """导入正常且版本可读。"""
    assert lingxi.__version__ == "0.1.0"


def test_main_prints_startup(capsys: pytest.CaptureFixture[str]) -> None:
    """入口函数可执行并输出启动信息。"""
    __main__.main()
    assert "lingxi" in capsys.readouterr().out
