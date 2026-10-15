# 后端静态检查

后端交付自检与构建门禁。规则文件与排除清单均在本目录，由根 pom 统一接入（8 个模块生效）。

## 引擎

| 引擎                        | 检查内容                                                                                                          | 规则文件                                                                                                                                                                            |
|-----------------------------|-------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| SpotBugs（字节码 bug 模式） | 空指针、资源泄漏、equals/hashCode、可疑集合用法等                                                                 | `spotbugs-exclude.xml`（排除清单）                                                                                                                                                  |
| Checkstyle（源码风格）      | 命名、导入、括号、空白、行长（≤150，存量分布上限口径）                                                            | `checkstyle.xml`                                                                                                                                                                    |
| PMD（源码静态分析）         | 未使用参数 / 私有方法 / 局部变量 / 私有字段                                                                       | `pmd-ruleset.xml`                                                                                                                                                                   |
| CPD（重复代码）             | 重复片段（**阈值 45 tokens**，与 IDEA 对齐；全库去重已完成，实测 0 处重复 —— 低于该阈值的小样板属设计固有，不追） | 阈值在根 pom 的**插件级** `<configuration>`（`cpd-check` 自身没有 `minimumTokens` 参数：官方文档写明它先触发 `cpd` 目标、再检查该报告，写进 `<execution>` 会被静默忽略且 IDE 报错） |
| Checkstyle MethodLength     | 方法长度上限 100 行（核心 pipeline 编排方法 60-100 行属项目结构特征）                                             | `checkstyle.xml`                                                                                                                                                                    |

## 触发节点

- **构建门禁**：`mvn verify` 阶段自动执行 spotbugs:check（阈值 Low）+ checkstyle:check + pmd:check + pmd:cpd-check，有告警构建失败；
- **推送门禁**：`tools/pre-push-gate.sh`（`.husky/pre-push` 转发，按推送范围触发；也可手动预跑）。全部步骤通过后写指纹缓存 `.git/workbuddy-prepush-ok`：同一次推送重试直接跳过，任何文件改动都让缓存失效并全量重跑；
- **交付自检**：写完代码后手动执行 `mvn compile spotbugs:check checkstyle:check pmd:check pmd:cpd-check`，0 告警才交付；
- **注释口径**：不在 `mvn verify` 里，由跨前后端的 `tools/check-comments.mjs` 检查（见 `tools/README.md`），是推送门禁的第 1 步；
- 检查须从仓库根目录执行（规则文件经 `${maven.multiModuleProjectDirectory}` 解析）。

## 排除口径

- `EI_EXPOSE_REP / EI_EXPOSE_REP2`：Lombok 生成访问器与 Spring 容器注入字段对外暴露可变对象——项目通用模式，防御性拷贝与 DTO 风格冲突且无实际收益；
- `REC_CATCH_EXCEPTION`：任务链与解析器统一捕获 Exception 归一化错误码——捕获范围即调用边界；
- `CT_CONSTRUCTOR_THROW`（SpillBuffer）：构造期 SHA-256 算法探测失败即抛，fail-fast 防御；
- `IS2_INCONSISTENT_SYNC / UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR`（PipelineTaskConsumer）：执行池字段仅启动期同步写入、运行期只读（配合 volatile 可见性）；
- `NP_*`（PdfBoxDocumentParser.buildLine / assemblePageElements、StructureAssemblerImpl.buildOrderRelations）：Hutool ObjectUtil 判空为项目统一风格，SpotBugs 无法识别其 null 语义产生误报，判空语义与原生 null 比较运行时等价。
- `UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR`（`PageQueryDto` 的 `current` / `size`）：查询 DTO 的字段由 Spring 数据绑定器反射写入、不靠构造器初始化，页码兜底用 Hutool `ObjectUtil.isNull` 判空，SpotBugs 不识别该方法的 null 语义，把紧随其后的拆箱比较判成"未初始化字段被解引用"；`||` 短路保证解引用时必非空，按**字段**豁免（该告警主元素是字段，写 `<Method>` 匹配不上）。首页最近提交复用同一个分页 DTO，豁免随字段一起落在 `PageQueryDto` 上。

## 等价性口径（相对 IDEA）

| IDEA 检查类别          | 等价引擎   | 残余差异                                                |
|------------------------|------------|---------------------------------------------------------|
| 数据流 / 空指针 / 资源 | SpotBugs   | IDEA 的字节码级路径更细，SpotBugs 按 bug 模式覆盖大部分 |
| 命名 / 导入 / 空白     | Checkstyle | IDEA 的实时排版提示无法完全复刻，规则集对齐存量风格     |
| 未使用参数/方法/字段   | PMD        | 覆盖私有方法/参数/局部变量/私有字段（IDEA 同款口径）    |
| 重复代码片段           | CPD        | 阈值按语句级重复标定；字段样板类重复为设计固有，不触发  |
| 恒真条件/冗余判断      | 无等价引擎 | IDEA 数据流级分析，Maven 生态无同精度替代——交付自查项   |
| Spring 注入专项        | 无等价引擎 | 以"应用可启动 + 单测全绿"兜底                           |

## 交付自查清单（机器覆盖不到的条款）

- [ ] 新增分支条件无"恒真/恒假"冗余判断（IDEA 数据流级，交付前人工核对）
- [ ] 新增 Serializable 实体带 serialVersionUID 并标注 @Serial（对齐 KbStrategyBinding 先例）
- [ ] 判空统一用 Hutool ObjectUtil（SpotBugs 误报走本文件排除清单，不改代码风格）
- [ ] 布尔方法命名与调用方向一致（无"恒取反"调用；IDEA 该建议无 Maven 等价规则）
- [ ] 新增子步骤/组装逻辑先查公共助手（StepLogHelper 等）再自行构造，避免低 token 重复片段
- [ ] 平行家族（各环节 TaskRunner / ControlServiceImpl / 解析器 / 切片策略 / 规则）先看有没有现成基类或助手（`TaskRunnerSupport`、`TaskDetailSupport`、`StageStrategySupport`、`AbstractTableSliceStrategy`、`AbstractPoiDocumentParser` 等），再决定是否新写一份
- [ ] Controller 查询参数优先复用共享查询对象（`PageQueryDto` / `StageTriggerQueryDto` / `StageDetailQueryDto` + `@ParameterObject`），注解样板不在每个方法上重写一遍
- [ ] 注释只写"做什么"（职责与行为），不写给谁用、为什么存在、从哪来、阶段/编号（前后端同一口径：前端见 `web/docs/前端开发规范.md` §9；机器检查见 `tools/check-comments.mjs`）
- [ ] 改了被依赖的模块（`workbuddy-common` / `workbuddy-infra` / `workbuddy-worker` 等）后，跑 `mvn -o -q install -DskipTests` 回写本地仓库。**推送钩子与 `mvn <goal>` 这类 CLI 调用用的是 `.m2` 里的 jar**（生命周期 `verify` 用 reactor 的新 classes，两者判定可能不同）：读到旧 jar 时的症状是 `cannot find symbol`，或 PMD 把"参数类型来自另一模块接口"的私有方法误报为未使用（`UnusedPrivateMethod`）
