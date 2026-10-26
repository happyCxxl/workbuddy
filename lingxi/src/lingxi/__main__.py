"""命令行入口：验证骨架可启动。"""

import io
import sys


def main() -> None:
    """启动灵犀骨架：仅打印启动信息，不含功能代码。"""
    if isinstance(sys.stdout, io.TextIOWrapper):
        # 固定标准输出为 UTF-8：Windows 控制台默认 GBK，不修则中文变乱码；测试替换 stdout 时跳过
        sys.stdout.reconfigure(encoding="utf-8")  # pyright: ignore[reportUnknownMemberType] 收窄后的流类型无泛型参数，方法本身在运行期存在
    print("lingxi 启动成功（骨架，无功能代码）")


if __name__ == "__main__":
    main()
