"""骨架冒烟：包可导入、入口可加载。"""

import lingxi
from lingxi import __main__


def test_import_and_version() -> None:
    """导入正常且版本可读。"""
    assert lingxi.__version__ == "0.1.0"


def test_entrypoint_exposes_main() -> None:
    """入口模块暴露 main 启动函数（不真正拉起服务，避免占用端口）。"""
    assert callable(__main__.main)
