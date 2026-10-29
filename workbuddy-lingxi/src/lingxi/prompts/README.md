# 静态提示词目录

组装模型输入用的静态文本与模板统一放这里（随包放在 `src/lingxi/prompts/`），代码只负责填占位符（`{{name}}`），不再硬编码长提示词。

| 文件                                 | 用途                  | 来源                                                                                              | 占位符                                                                                                  |
|------------------------------------|---------------------|-------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------|
| `behavior.md`                      | 行为与安全准则（静态前缀第 1 段）  | 参考 Claude Code 主提示的 Doing tasks / Executing actions / Using your tools / System 段落，中文改写并按灵犀工具收敛 | 无                                                                                                    |
| `capability.md`                    | 能力与规则段（工具清单 + 执行约定） | 灵犀自有：工具清单由注册表动态生成；「专用工具优先」思路参考 Claude Code Using your tools                                     | `tools`                                                                                              |
| `persona.md`                       | 人格段模板（8 字段 + 硬底线）   | 灵犀需求文档 §人格（八字段 + 四条硬底线），非外部提示词                                                                  | `name` `user_name` `opening` `catchphrases` `tone` `style` `boundaries` `user_profile` `hard_limits` |
| `sub_session.md`                   | 子会话执行器声明            | 参考 DSH 子代理的委派声明（权限启动时固定、审批自动拒绝、限制写进回复），按灵犀 S17 收敛                                               | 无                                                                                                    |
| `source_chat.md` / `source_sub.md` | 来源声明（对话 / 子会话）      | 灵犀自有（四段装配约定）                                                                                    | 无                                                                                                    |
| `runtime.md`                       | 运行时上下文（动态尾巴）        | 灵犀自有；字段结构参考 Claude Code Environment 段（cwd/平台/时间）                                                | `time` `work_dir` `platform` `platform_code`                                                         |
| `memory.md`                        | 记忆资料段（动态尾巴）         | 灵犀需求文档 §记忆（编号 + 类型 + 来源日期）                                                                      | `items`                                                                                              |

说明：Claude Code 的系统提示词没有官方完整发布，行为准则参考的是社区整理的泄露版主提示（结构与原则），均为**中文改写**而非逐字复制；外部提示词只作为设计参考，落地内容以灵犀需求与工具边界为准。

约定：

- 加载器 `lingxi.core.prompts`：`load_prompt(name)` 读文件，`render_prompt(name, **values)` 替换占位符；**模板中不得残留未替换的 `{{...}}`**，否则直接报错（防止漏传参数悄悄上线）。
- 顺序决定缓存：静态段（behavior → capability → persona → source）**逐字节稳定**；动态段（runtime → memory）与历史之后的内容按轮变化，不要挪到静态段前面。
- 新增提示词：在这里加 `.md` 文件并在此表登记；不要写回代码常量。
