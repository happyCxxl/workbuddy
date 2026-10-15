# 组装环节 · 功能逻辑

执行链第二环：把解析产物焊接成知识库内部唯一稳定的结构产物（`UnifiedDocument`），交给预处理环节。本目录记**环节内部的功能逻辑**（流程、口径、契约、阈值）；页面梳理（卡片四态、体检清单与详情抽屉、四个接口的请求响应）在 `../../pages/组装环节/`，市面做法对照与出处见 `../../research/组装环节-开源方案调研.md`。

## 环节定位

| 项   | 值                                                                                                                                    |
|------|---------------------------------------------------------------------------------------------------------------------------------------|
| 上游 | 解析环节（`ParseResult.sources` 产物）；任务编排（biz 侧 `StructureTaskRunner` 领取组装任务）                                         |
| 输入 | `AssembleContext`（`taskId` / `fileResultId` / 源文件类型 / 组装阈值）+ 上游 `ParseResult`                                            |
| 输出 | `UnifiedDocument`（`documentInfo` / `pages` / `elements` / `relations` / `quality`）+ 五个步骤日志 + 建议状态                         |
| 触发 | `KnowledgeFileStructureController` 的触发接口 → `TaskTriggerSupport` 建任务入队 → `StructureTaskRunner` 领取并调用管线                |
| 下游 | 预处理环节（消费 `UnifiedDocument.elements` 与 `pages[].marks`）；切片、向量化、检索沿执行链继续                                      |
| 落库 | 不在本环节内：`AssemblerPipeline` 是纯算法，不碰 DB 与对象存储；产物落库与任务终态由 biz 侧编排完成                                   |
| 实现 | 规则路径：七道工艺全为固定规则；模型能力（标题判定兜底）当前零实现                                                                    |
| 代码 | `../../../workbuddy-worker/src/main/java/com/knowledge/worker/structure/`（阈值在 `StructureProperties`，前缀 `knowledge.structure`） |

## 文档导航

| 文档                                      | 管什么                                                                                   | 什么时候看                   |
|-------------------------------------------|------------------------------------------------------------------------------------------|------------------------------|
| `01-功能逻辑.md`                          | 七道工艺的口径、标题规则链、续表判定、关系重建、溯源与噪声、失败语义、阈值清单、产物契约 | 改组装行为、排查组装结果异常 |
| `02-流程图-环节总览.html`                 | 读上游产物 → 七道工艺 → 关系重建 → 产物与质量落库的主链（可交互，节点带代码溯源）        | 理清环节整体走向             |
| `03-流程图-七道工艺.html`                 | 七道工艺各自的输入、判定与产出（标准化 / 去重 / 顺序 / 结构 / 接续 / 溯源 / 噪声）       | 改某一工艺的实现             |
| `../../pages/组装环节/README.md`          | 卡片与抽屉四态、体检清单五行、四个接口的请求响应、后端统计汇总                           | 改页面、联调接口             |
| `../../research/组装环节-开源方案调研.md` | 市面方案对照（可直接照搬 / 需改造 / 不适用）与出处                                       | 评估新能力、找口径依据       |

两张流程图是 `archify` 生成的单文件 HTML（内联 SVG，浏览器直接打开，可缩放与切换明暗主题、导出 PNG / SVG），节点上的溯源指向 `meta.repository` 里固定的提交。生成源（`candidate.json`，可重跑）与门禁回执（`finalize` / `finalize-summary` / `browser-check` / `delivery`）在同名 `.json` 文件里，放在 `图源/`。

通用口径（响应体形状、错误码分段、鉴权、判空约定）见 `../../README.md` 的「全局约定」章，本目录不复述。

## 代码索引

| 位置                                        | 职责                                                                                                                       |
|---------------------------------------------|----------------------------------------------------------------------------------------------------------------------------|
| `structure/impl/AssemblerPipeline`          | 环节编排：标准化 → 去重与阅读顺序 → 结构组装 → 跨页接续 → 关系重建 → 溯源校验 → 重复与噪声 → 质量判定                      |
| `structure/DocumentAssemblerPort`           | 组装契约：`assemble(parseResult, context)` 返回 `AssembleOutcome`                                                          |
| `structure/AssembleContext`                 | 组装输入：`taskId`、`fileResultId`、源文件类型、`StructureProperties`                                                      |
| `structure/StructureProperties`             | 全部组装阈值（`knowledge.structure` 前缀）                                                                                 |
| `structure/craft/ElementNormalizer`         | 工艺① 元素标准化（实现 `impl/craft/NormalizerImpl`，产出 `NormalizeOutcome`）                                              |
| `structure/craft/DedupMerger`               | 工艺② 跨来源去重合并（实现 `impl/craft/DedupMergerImpl`，产出 `MergeOutcome`）                                             |
| `structure/craft/ReadingOrderResolver`      | 工艺③ 阅读顺序 XY-cut（实现 `impl/craft/ReadingOrderResolverImpl`）                                                        |
| `structure/craft/StructureAssembler`        | 工艺④ 标题推定与章节插入 + 关系重建 `buildRelations`（实现 `impl/craft/StructureAssemblerImpl`）                           |
| `structure/title/`                          | 标题规则链契约（`TitleRule` / `TitleDecision` / `TitleRuleContext` / `TitleEvidence`）                                     |
| `structure/impl/title/`                     | 七条标题规则：Style / Chapter / SingleNumber / Number / CnParen / CnDot / FontSignal                                       |
| `structure/judge/`                          | 模型判定能力契约：`StructureJudgeProvider`（标题与续表两路，当前零实现）                                                   |
| `structure/craft/TableContinuationResolver` | 工艺⑤ 跨页续表接续（实现 `impl/craft/TableContinuationResolverImpl`，产出 `ContinuationOutcome`）                          |
| `structure/craft/RepeatNoiseMarker`         | 工艺⑦ 重复与噪声识别（实现 `impl/craft/RepeatNoiseMarkerImpl`，产出 `MarkOutcome`）                                        |
| `structure/craft/`（其余）                  | 产出载体：`TreeOutcome` / `NormalizeOutcome` / `MergeOutcome` / `ContinuationOutcome` / `MarkOutcome`                      |
| `common/enums/structure/`                   | 枚举口径：`UnifiedElementType` / `RelationType` / `ElementMark` / `PageMark` / `ElementExtensionKey` / `StructureStepName` |
| `biz/task/runner/StructureTaskRunner`       | 领任务 → 读上游产物 → 调管线 → 写产物行与子步骤 → 回写终态（原子条件更新）                                                 |
| `biz/service/support/StructureStatsSupport` | 详情页统计与结论文案（按产物现算，不落库）                                                                                 |

## 待定项

只列**环节逻辑**尚未决定的问题；页面交互类在 `../../pages/组装环节/README.md` 的待定项。

- **多路来源的触发与裁决口径未定**：OCR / 版面 / 表格等路的触发规则归解析环节，重叠区域的裁决规则归组装环节；业界没有公开的来源优先级表，当前 `SOURCE_PRIORITY` 只是临时顺序，接入第二路后要改按证据裁决。
- **标题规则链的证据强度未标定**：七条规则的阈值（字号倍数、编号模式）是草案值，没有在代表性样本上标定误判率。
- **模型兜底零实现**：标题与续表两路契约、注册表与开关都在，`knowledge.structure.model-fallback.enabled` 默认关闭；接入实现时需要给注册表补多实现的优先级口径。
- **溯源与告警的阈值只做了一半**：产物告警与详情页结论文案已统一到 80%，但"低于阈值才报"与"缺一个就报"之间的取舍只按 80 落地，未做样本验证。
- **重复判定的集合缓存容量**：按需重建的 bigram 集合缓存上限取 `knowledge.structure.repeat-set-cache-size`（默认 64），是否需要按文档规模自适应尚未决定；判定结果与缓存容量无关。
- **判定阈值是否上收为可调策略**：噪声页（无元素页 / 仅图片页 / 乱码率 > `noise-garbled-ratio`）、重复页与重复段（Jaccard 阈值 / 最小长度）目前只在 `knowledge.structure` 配置里（运维级，页面上看不到），预处理策略只按这些标记决定处置方式；是否把这几个判定阈值做成组装策略的可调项尚未决定，解析侧同类问题（页眉页脚判定）见 `../解析环节/README.md` 的待定项。
- **噪声页清单的呈现方式**：产物告警是"计数 + 最多 3 条样例"，完整清单只进日志；是否改成结构化字段（前端逐条渲染、可点击跳到该页）尚未决定。
- **组装报告不外发**：`AssembleReport` 只服务管线内判定与日志（不落产物、不落子步骤记录），详情页统计由产物现算；是否把关键计数并入产物质量区尚未决定。
- **产物行的能力快照列语义**：组装环节没有独立能力快照，`wb_knowledge_pipeline_product.capability_snapshot` 只承载产物 schemaVersion；是否统一为「环节策略快照」语义尚未决定。
- **预留字段的启用时机未定**：`assets`（二进制资源引用）、`orderCuts`（顺序切分数）、`candidateOrder`（候选顺序）、`headerRepeated`（页眉重复标记）、`rotation`（页面旋转）、`bboxes`（跨页分段框）都已在契约注释里标注为预留未实现或无消费方，启用时机随对应能力接入。
- **页路径只对 PDF 生效**：`REPEATED_PAGE` / `NOISE_PAGE` 是页级标记，Word / Excel 没有页概念（Excel 有 sheet 章节但无页码）；Office 系是否补"虚拟页"口径尚未决定。
- **`repeatSetCacheSize` 与文档规模的关系未验**：缓存命中率与文档规模的相关性没有实测数据，默认 64 属经验值。

核对代码时读出的**注释与实现不一致、或行为未有定论**的点，记在这里待定（均以代码实现为准，文档按实现写）：

| 位置                                  | 现象                                         | 待定                   |
|---------------------------------------|----------------------------------------------|------------------------|
| `AssembleReport.orderCuts`            | 字段声明但从未赋值                           | 接顺序切分统计还是移除 |
| `ParseSource.candidateOrder`          | 契约字段，组装环节阅读顺序不消费             | 接消费方还是收掉       |
| `UnifiedElement.bboxes`               | 跨页分段框有产出、无生产读取方               | 接预览分段高亮还是收掉 |
| `ElementExtensionKey.HEADER_REPEATED` | 无解析器写入、无读取方                       | 补产出方还是收掉       |
| `UnifiedDocument.assets`              | 始终为空列表                                 | 接入资源引用还是移除   |
| `AssemblerPipeline` 噪声样例条数      | 常量 `NOISE_SAMPLE_CAP = 3` 硬编码，未进配置 | 是否配置化             |
| `SOURCE_PRIORITY`                     | 临时顺序，注释已声明为过渡口径               | 接入第二路时改证据裁决 |
