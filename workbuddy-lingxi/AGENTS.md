# workbuddy-lingxi/AGENTS.md — 灵犀功能域入口

> 编辑 `workbuddy-lingxi/` 下任何文件时自动加载。全局口径（注释、提交、门禁、依赖、--no-verify 禁令）以根 `AGENTS.md` 为准，此处不重复。

## 1. 定位与命令

- 灵犀：带人格与记忆的个人 AI 助手；Python 3.12 + Pydantic + SQLAlchemy + pgvector（规划中），当前为可启动骨架。
- 与业务后端共用登录体系：只验签、不发令牌、不建用户表；数据独立。

| 场景     | 命令                                                                                 | 要点                                                                     |
|----------|--------------------------------------------------------------------------------------|--------------------------------------------------------------------------|
| 安装同步 | `uv sync`                                                                            | 增量安装；`uv.lock` 入库；首次需建 `config.local.toml`（敏感值，不入库） |
| 启动验证 | `uv run python -m lingxi`                                                            | 打印启动信息                                                             |
| 全量校验 | `uv run ruff check && uv run ruff format --check && uv run pyright && uv run pytest` | 推送门禁与 CI 同套；覆盖率阈值 70%                                       |
| 单测     | `uv run pytest`                                                                      | pytest-randomly 随机顺序                                                 |
| 重置开发库 | `uv run alembic downgrade base && uv run alembic upgrade head`                     | 清空全部数据并重灌人格 v1 种子；仅本机开发库                              |

## 2. 灵犀 Never（违反即不合规；完整条款以规范正典为准）

1. 模型调用只经统一出口，业务代码禁止直接实例化供应商 SDK（PY-AI-001）。
2. 记忆可查可删：删除物理移除、不留副本；低置信度记忆不得向用户断言（PY-AI-008）。
3. 身份只验签：比对令牌签名与版本，禁止签发、禁止建用户表（身份源在业务后端）。
4. 只访问自己的库：跨服务数据经 API 或事件，禁止共享库与跨库 join（PY-DATA-008）。
5. 进出参一律 Pydantic 模型，裸 `dict` 只在与外部系统对接的边界出现（PY-DTO-001）。

## 3. 必读与交付

- 动手前必读：`.agent/standards/python/00-总则与规则索引.md`（基底声明与编号域；正文在 python/01–07，风格基底是 Google Python Style Guide）。
- 交付自查：`.agent/standards/python/00-总则与规则索引.md` §6 的评审项（机器层已由钩子覆盖，评审层逐项核对）。
- 检查器组成与豁免口径：`.agent/standards/common/07-检查手段.md`。
