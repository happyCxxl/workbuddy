"""命令行入口：以 uvicorn 启动灵犀服务。"""

import io
import sys

import uvicorn

from lingxi.app.config import Settings
from lingxi.app.main import create_app


def main() -> None:
    """启动服务：固定 UTF-8 标准输出后拉起 uvicorn。"""
    if isinstance(sys.stdout, io.TextIOWrapper):
        # 固定标准输出为 UTF-8：Windows 控制台默认 GBK，不修则中文变乱码；测试替换 stdout 时跳过
        sys.stdout.reconfigure(encoding="utf-8")  # pyright: ignore[reportUnknownMemberType] 收窄后的流类型无泛型参数，方法本身在运行期存在
    settings = Settings()
    uvicorn.run(create_app(settings), host=settings.host, port=settings.port)


if __name__ == "__main__":
    main()
