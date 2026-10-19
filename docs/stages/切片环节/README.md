# 切片环节 · 功能逻辑

执行链第四环：把预处理派生视图（`PreprocessView`）切成检索命中的最小单元（`Chunk`），交给向量化环节。本目录记**环节内部的功能逻辑**（流程、口径、契约、参数）；页面梳理（卡片体检清单、详情抽屉与品质视角、三个接口的请求响应）在 `../../pages/切片环节/`。

两条不容混淆的事实：**片内容取 `normalizedText` 口径**（检索用文本，剔除态为 `null` 切不出片）；**切片不改上游**（预处理产物只读，不写 `wb_file_result`、不回写预处理任务）。平台默认每个章节额外产出一条 `contentType=SECTION` 的**父片**，它计入片数但默认不产向量（向量化侧 `includeParent=OFF`），即"片数"不等于"检索条数"。

## 环节定位

| 项   | 值                                                                                                                                                                          |
|------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 上游 | 预处理环节（`PreprocessView`：元素、状态、`normalizedText`、`titlePath` 相关结构）；任务编排（biz 侧 `ChunkTaskRunner` 领取切片任务）                                       |
| 输入 | `ChunkContext`（`view` 派生视图 / `document` 结构产物 / `strategy` 策略快照 / `properties` 全局默认 / `fileResultId` / `upstreamProductRef`）                               |
| 输出 | `ChunkOutcome`（`chunkSet` 切片集合 + 六条子步骤日志 + `warnings` + `suggestedStatus`）                                                                                     |
| 触发 | `KnowledgeFileChunkController` 的触发接口 → 策略两档解析生效版本（显式指定 → 知识库绑定）→ 上游产物校验 → 执行键查重 → 快照进任务 → 入队 → `ChunkTaskRunner` 领取并调用管线 |
| 下游 | 向量化环节（父片默认跳过；片长超过模型窗口允许字符数时向量化直接失败 `EMBED_MODEL_INCOMPATIBLE`）；检索侧 `PARENT_EXPAND` 预留                                              |
| 落库 | 不在本环节内：`ChunkPipeline` 是纯算法（不碰 DB 与对象存储）；产物行与任务终态由 biz `ChunkTaskRunner` 编排                                                                 |
| 实现 | 四路由（正文 / 表格 / 图片 / 兜底降级）+ 固定后置链（结构重叠 → 碎片合并 → 标题入正文）+ 单层父子片；算法按 `(route, key)` 注册表查找                                       |
| 代码 | `../../../workbuddy-worker/src/main/java/com/workbuddy/worker/chunking/`（全局默认值在 `ChunkProperties`，前缀 `knowledge.chunk`）                                          |

## 文档导航

| 文档                             | 管什么                                                                                           | 什么时候看                   |
|----------------------------------|--------------------------------------------------------------------------------------------------|------------------------------|
| `01-功能逻辑.md`                 | 四路由与算法目录、正文聚合与降级、表格三算法、后置链、父子与编号、统计口径、参数目录、失败语义   | 改切片行为、排查切片结果异常 |
| `02-流程图-环节总览.html`        | 读上游视图 → 触发与快照 → 执行器与切片管线 → 产物与落库 → 向量化与检索（可交互，节点带代码溯源） | 理清环节整体走向             |
| `03-流程图-四路由与算法链.html`  | 跳过过滤 → 标题章节栈 → 四路由分派（表格可并入正文流）→ 兜底降级 → 后置链 → 父子与编号           | 改某条路由或某个算法         |
| `../../pages/切片环节/README.md` | 卡片与抽屉、体检清单、三个接口的请求响应、统计口径                                               | 改页面、联调接口             |

两张流程图是 `archify` 生成的单文件 HTML（内联 SVG，浏览器直接打开，可缩放与切换明暗主题、导出 PNG / SVG），节点上的溯源指向 `meta.repository` 里固定的提交。生成源（`candidate.json`，可重跑）与门禁回执（`finalize` / `finalize-summary` / `browser-check` / `delivery`）在同名 `.json` 文件里，放在 `图源/`。

通用口径（响应体形状、错误码分段、鉴权、判空约定）见 `../../README.md` 的「全局约定」章，本目录不复述。

## 代码索引

| 位置                                                                               | 职责                                                                                                                        |
|------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------|
| `chunking/impl/ChunkPipeline`                                                      | 环节编排：跳过过滤 → 标题章节栈 → 四路由分派 → 后置链（逐章）→ 父子片 → 编号与 token → 集合组装与状态判定                   |
| `chunking/ChunkerPort`                                                             | 切片契约：`chunk(context)` 返回 `ChunkOutcome`                                                                              |
| `chunking/ChunkContext` / `SliceContext`                                           | 运行上下文：上游产物、策略、全局默认；切片期共享状态（正文缓冲、标题栈、前导段落、兜底切片器、元素索引）                    |
| `chunking/ChunkProperties`                                                         | 全部切片默认值（`knowledge.chunk` 前缀，application.yml 同名键）                                                            |
| `chunking/WindowSlicer`                                                            | 窗口切片工具（固定长度 + 重叠的硬切）                                                                                       |
| `chunking/slice/SliceStrategy` / `ChunkPostProcessor` / `FallbackSlicer`           | 三类扩展点契约：路由切片器 / 后置处理器 / 兜底切片器                                                                        |
| `chunking/impl/AlgorithmRegistry`                                                  | 按 `(route, key)` 建索引的泛型注册表（未找到明确报错，不静默降级）                                                          |
| `chunking/impl/SliceStrategyRegistry`                                              | 路由切片器注册表（排除 FALLBACK 路由）                                                                                      |
| `chunking/impl/FallbackSlicerRegistry`                                             | 兜底切片器注册表                                                                                                            |
| `chunking/impl/body/AbstractBodySliceStrategy`                                     | 正文聚合骨架：跨元素缓冲、空文本丢弃、超长降级、累计结算                                                                    |
| `chunking/impl/body/ParagraphSliceStrategy`                                        | 段落聚合（默认）：元素边界结算，单元素超软上限先结算再降级                                                                  |
| `chunking/impl/body/TitleBoundarySliceStrategy`                                    | 标题边界：一节一片，超片长上限走兜底降级                                                                                    |
| `chunking/impl/body/StructureHybridSliceStrategy`                                  | 结构混合：标题边界 + 段落聚合混合                                                                                           |
| `chunking/impl/body/SentenceAggregateSliceStrategy`                                | 句子聚合：跨元素按句聚合                                                                                                    |
| `chunking/impl/body/FixedWindowSliceStrategy`                                      | 正文固定窗口 + 重叠                                                                                                         |
| `chunking/impl/body/BodyChunkSupport`                                              | 正文共用工具：切片构建、页码合并、缓冲结算、句边界切分、**超长降级唯一入口与降级原因常量**                                  |
| `chunking/impl/table/AbstractTableSliceStrategy` / `TableMarkdownSupport`          | 表格切片骨架与 Markdown 序列化（管道表 + 表头随片）                                                                         |
| `chunking/impl/table/TableRowSliceStrategy`                                        | 行级切片（默认）：短行按行组聚合、长行单行成片                                                                              |
| `chunking/impl/table/TableRowGroupStrategy`                                        | 行组切片：行数与字符双上限结算                                                                                              |
| `chunking/impl/table/TableWholeStrategy`                                           | 整表一片：超过片长上限降级为行级（固定 30/3，不读行级参数）                                                                 |
| `chunking/impl/table/TableContextMergedStrategy`                                   | 表 + 引导段落：每片前缀该表前的最近正文段（截断到上限）                                                                     |
| `chunking/impl/image/ImageCaptionSliceStrategy`                                    | 图片：图注占位成片（有图注拼接，无图注占位文本；OCR 预留）                                                                  |
| `chunking/impl/fallback/RecursiveLengthFallback`                                   | 兜底（默认）：先按句边界切，超长句再按固定长度 + 重叠硬切                                                                   |
| `chunking/impl/fallback/FixedWindowFallback` / `NoneFallback`                      | 兜底：纯窗口硬切 / 不兜底（超长原样单片）                                                                                   |
| `chunking/impl/post/StructureOverlapProcessor`                                     | 后置①结构重叠：本片前缀上一片末尾 N 字符（默认 0 = 不处理）                                                                 |
| `chunking/impl/post/MinMergeProcessor`                                             | 后置②碎片合并：正文碎片并入同章紧邻的前一个正文片（不跨表格 / 图片 / 章节）                                                 |
| `chunking/impl/post/TitleInContentProcessor`                                       | 后置③标题入正文：非父片首行拼 `titlePath`，父片跳过，`charCount` 重算                                                       |
| `chunking/strategy/ChunkStrategy`                                                  | 策略快照模型：四路由配置 + 流程层键；取值读取与算法解析                                                                     |
| `chunking/strategy/ChunkAlgorithmSpec`                                             | 默认补齐（归一化）与保存校验（biz 调用）；参数范围取自参数目录                                                              |
| `chunking/strategy/ChunkStrategyParser`                                            | 快照 JSON → 策略对象（触发侧与控制面读取共用口径，失败回退内置默认）                                                        |
| `chunking/strategy/ChunkRouteConfig` / `ChunkParamKeys`                            | 单路由配置（算法 + 参数）；参数键的 JSON 契约常量                                                                           |
| `chunking/strategy/ChunkWindowEstimator`                                           | 按策略估算片长上界（供向量化窗口校验参考）                                                                                  |
| `common/enums/chunk/ChunkAlgorithm`                                                | 算法目录（19 项 = 13 上线 + 6 预留）：`(route, key)`、支持状态、正文 flush 语义                                             |
| `common/enums/chunk/ChunkParam`                                                    | **参数目录**：按 `(算法, 参数键)` 的取值范围与说明（保存校验与页面提示的唯一事实源）                                        |
| `common/enums/chunk/ChunkRoute` / `ChunkContentType` / `ChunkKind` / `PipelineKey` | 路由（四值）/ 片类型（`SECTION` / `PARAGRAPH` / `TABLE` / `IMAGE` / `FALLBACK`）/ 切片路（三值）/ 流程层键（六项）          |
| `common/domain/chunk/Chunk` / `ChunkSet` / `ChunkOutcome`                          | 产物契约：单片（内容 / 类型 / 标题路径 / 来源 / 页码 / 父子 / 顺序 / token / 兜底原因）、集合、运行产出                     |
| `common/domain/rules/PreprocessViewRules`                                          | 跳过名单：`CHUNK_SKIP_STATUSES`（与预处理同一份口径）                                                                       |
| `biz/task/runner/ChunkTaskRunner`                                                  | 领任务 → 读上游视图 → 调管线 → 落产物（产物 JSON + `wb_knowledge_chunk_set` / `wb_knowledge_chunk`）与六条子步骤 → 回写终态 |
| `biz/service/impl/ChunkControlServiceImpl`                                         | 控制面：策略快照读取、详情与统计读取                                                                                        |
| `biz/service/support/ChunkStatsSupport`                                            | 详情统计与结论文案（按产物现算，24 个键）                                                                                   |

## 待定项

只列**环节逻辑**尚未决定的问题；页面交互类在 `../../pages/切片环节/README.md` 的待定项。

- **`tokenCount` 是字符估值**：`ceil(字符数 ÷ 1.5)`，与嵌入模型的真实 token 不是一回事；开源做法建议切片器与嵌入模型共用同一 tokenizer，是否接入真分词尚未决定。
- **结构重叠默认关**：`structureOverlap` 默认 0，开源多数默认开；是否改默认值需要按检索重复命中的实测数据决定。
- **整表降级的分组不可调**：`whole-table` 超限降级为行级时固定 30/3，不读行级参数；是否把这两个参数也接到该算法尚未决定。
- **欠长片数是否上页面未定**：统计已给 `underMinMergeCount` / `minMergeLen`（与超目标、超软上限片数成对），卡片与详情是否展示尚未决定。
- **父子只有单层**：每章一条父片；多级父子与"命中子片自动回溯父片全文"（层级节点 + 自动合并）暂不做。
- **父片内容无截断**：父片 = 该章全部子片 content 拼接，长章节的父片可能远超模型窗口；是否截断或摘要尚未决定。
- **RAGFlow 之外的 chunk method 未核对**：调研第六节列了 6 项未展开核对的内容（LangChain 分隔符优先级、LlamaIndex 层级参数、RAGFlow chunk method、Unstructured `by_title` 执行顺序、chonkie/语义切片、Docling `contextualize` 是否含表头）。
- **策略页没有文档族**：`../../README.md` 的页面清单里「策略管理」仍是"待梳理"；切片配置页的口径暂记在 `03-接口逻辑.md` 的参数范围表与 `04-自测清单.md` 的策略页一节。
- **`wb_knowledge_chunk` 没有溯源列**：溯源只在产物与向量库索引里，DB 侧做不了"按来源元素反查片"；是否改表尚未决定。
- **`sourceElementCount` / `fallbackReason` 只在详情下发**：`stage-content` 的 `extra` 没有这两个键（要带上得每次再读一遍产物），是否补进 `extra` 尚未决定。
- **片数含不产向量的父片**：卡片「切片条数」是含父片的总数，是否并列一个"产向量片数"尚未决定。
- **`stage-content` 的过滤维度**：当前只有内容类型 / 兜底 / 有无父片三项，是否扩到标题路径、字数区间尚未决定。

核对代码时读出的**注释与实现不一致、或行为未有定论**的点，记在这里待定（均以代码实现为准，文档按实现写）：

| 位置                                       | 现象                                                                                                  | 待定                                           |
|--------------------------------------------|-------------------------------------------------------------------------------------------------------|------------------------------------------------|
| `ChunkAlgorithm.BODY_SEMANTIC` 等 6 个预留 | 枚举里 `supported=false`，前端置灰并写明原因；算法键已经在 JSON 契约里                                | 上线时是否需要迁移旧快照                       |
| `ChunkPipeline` 的"兜底切片"子步骤         | `matched` 有值但 `durationMs` 恒 0（兜底在切片器内部触发，无法单独计时）                              | 是否给兜底单独计时                             |
| `ChunkAlgorithmSpec.validateInvariants`    | 兜底不变量读的是 `fallback.params`（与所选兜底算法无关），选 `none` 时残留 `len`/`overlap` 仍会被校验 | 是否按所选算法收紧                             |
| `ChunkStatsSupport.countUnder`             | 欠长片数只统计非父片                                                                                  | 是否把父片也算进来（父片天然偏长，通常不需要） |
