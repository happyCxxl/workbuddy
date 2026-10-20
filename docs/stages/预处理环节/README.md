# 预处理环节 · 功能逻辑

执行链第三环：把组装产物（`UnifiedDocument`）处理成**检索口径的派生视图**（`PreprocessView`），交给切片环节。本目录记**环节内部的功能逻辑**（流程、口径、契约、阈值）；页面梳理（卡片体检清单、详情抽屉、三个接口的请求响应）在 `../../pages/预处理环节/`。

判据与处置在本环节是分开的：**谁算页眉页脚 / 目录 / 重复段 / 噪声页，由解析与组装环节判定**（结构标记），本环节只按策略决定"怎么处置、文本怎么整理"。要改判定阈值，改上游环节；要改判定结果的处理方式，才在本环节。

## 环节定位

| 项   | 值                                                                                                                                                                                    |
|------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 上游 | 组装环节（`UnifiedDocument`：`elements` 与 `pages[].marks`）；任务编排（biz 侧 `PreprocessTaskRunner` 领取预处理任务）                                                                |
| 输入 | `PreprocessContext`（`document` / `strategy` 策略快照 / `properties` / `fileResultId` / `upstreamProductRef`）                                                                        |
| 输出 | `PreprocessOutcome`（`view` 派生视图 + 九条步骤日志 + `warnings` + `suggestedStatus`）                                                                                                |
| 触发 | `KnowledgeFilePreprocessController` 的触发接口 → 策略两档解析生效版本（显式指定 → 知识库绑定）→ 上游产物校验 → 执行键查重 → 快照进任务 → 入队 → `PreprocessTaskRunner` 领取并调用管线 |
| 下游 | 切片环节（按 `PreprocessViewRules.CHUNK_SKIP_STATUSES` 跳过不进内容流的元素）；向量化、检索沿执行链继续                                                                               |
| 落库 | 不在本环节内：`PreprocessPipeline` 是纯算法，不碰 DB 与对象存储；产物行与任务终态由 biz 侧编排完成                                                                                    |
| 实现 | 八条规则固定顺序、逐条按策略开关与档位生效；策略在**触发时快照**，执行只读快照（可复现）                                                                                              |
| 代码 | `../../../workbuddy-worker/src/main/java/com/workbuddy/worker/preprocessing/`（全局默认值在 `PreprocessProperties`，前缀 `knowledge.preprocess`）                                     |

原文零改动是硬约束：处置只落在派生视图副本上（`rawText` 从不修改），视图元素带 `status` / `displayText` / `normalizedText` / `normalizedFields` / `preprocessTrace`。

## 文档导航

| 文档                               | 管什么                                                                                                | 什么时候看                     |
|------------------------------------|-------------------------------------------------------------------------------------------------------|--------------------------------|
| `01-功能逻辑.md`                   | 九步管线的口径、三层文本、处置状态、剔除与统计口径、策略模型、失败语义、阈值清单、产物契约            | 改预处理行为、排查清洗结果异常 |
| `02-流程图-环节总览.html`          | 读上游产物 → 九步管线 → 派生视图与统计落库的主链（可交互，节点带代码溯源）                            | 理清环节整体走向               |
| `03-流程图-八步规则链.html`        | 八条规则各自的判定输入、处置分支与产出（编码 / 整理 / 页眉页脚 / 目录 / 重复 / 字段 / 噪声 / 自定义） | 改某一条规则的实现             |
| `../../pages/预处理环节/README.md` | 卡片与抽屉、体检清单、三个接口的请求响应、统计口径                                                    | 改页面、联调接口               |

两张流程图是 `archify` 生成的单文件 HTML（内联 SVG，浏览器直接打开，可缩放与切换明暗主题、导出 PNG / SVG），节点上的溯源指向 `meta.repository` 里固定的提交。生成源（`candidate.json`，可重跑）与门禁回执（`finalize` / `finalize-summary` / `browser-check` / `delivery`）在同名 `.json` 文件里，放在 `图源/`。

通用口径（响应体形状、错误码分段、鉴权、判空约定）见 `../../README.md` 的「全局约定」章，本目录不复述。

## 代码索引

| 位置                                                 | 职责                                                                                                                       |
|------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------|
| `preprocessing/impl/PreprocessPipeline`              | 环节编排：预计算（重复页 / 噪声页 / 目录计数与窗口 / 自定义正则预编译）→ 逐元素建副本 → 按 `order` 执行启用规则 → 视图组装 |
| `preprocessing/PreprocessContext`                    | 输入：上游产物、策略、全局默认值、产物引用                                                                                 |
| `preprocessing/PreprocessOutcome`                    | 产出：派生视图、步骤日志、告警、建议状态                                                                                   |
| `preprocessing/PreprocessProperties`                 | 全局默认值（`knowledge.preprocess` 前缀）：目录行数下限、连续窗口下限                                                      |
| `preprocessing/rule/CleanRule`                       | 规则契约：`name` / `stepName` / `order` / `enabledIn` / `apply`                                                            |
| `preprocessing/rule/RuleContext`                     | 规则执行上下文：策略、预计算的页集合与目录窗口、自定义正则匹配器                                                           |
| `preprocessing/rule/CustomRuleMatcher`               | 预编译后的自定义正则（pattern + 动作 + 替换文本 + 原文）                                                                   |
| `preprocessing/impl/rule/EncodingCleanRule`          | ① 编码规范化 `encoding-clean-v1`（乱码残留 + NFKC，产出三层文本基座）                                                      |
| `preprocessing/impl/rule/TextTidyRule`               | ② 段落/表格文本整理 `text-tidy-v1`（六项子规则，含折行段落合并）                                                           |
| `preprocessing/impl/rule/HeaderFooterRule`           | ③ 页眉页脚处置（按解析识别出的 `HEADER` / `FOOTER` 元素）                                                                  |
| `preprocessing/impl/rule/TocRule`                    | ④ 目录处置（页内行数或连续窗口判定，阈值对三种处置都生效）                                                                 |
| `preprocessing/impl/rule/RepeatRule`                 | ⑤ 重复处置（组装标记的重复页 / 重复段，除首份外）                                                                          |
| `preprocessing/impl/rule/FieldNormalizeRule`         | ⑥ 字段规范化 `field-normalize-v1`（金额 / 日期 / 面积 / 证号，四类子开关）                                                 |
| `preprocessing/impl/rule/NoiseRule`                  | ⑦ 噪声处置（组装标记的噪声页上的元素）                                                                                     |
| `preprocessing/impl/rule/CustomCleanRule`            | ⑧ 自定义规则 `custom-clean-v1`（链尾，按序施加用户正则）                                                                   |
| `preprocessing/impl/rule/MarkDisposeSupport`         | 标记类规则共用的三档处置（剔除 / 保留 / 标记）；剔除档清检索文本与单元格检索文本                                           |
| `preprocessing/impl/rule/ViewElementHelper`          | 视图元素通用操作（清单元格文本等）                                                                                         |
| `preprocessing/strategy/PreprocessStrategy`          | 策略快照模型：七规则配置 + 自定义规则组；取值读取与旧开关换算                                                              |
| `preprocessing/strategy/PreprocessAlgorithmSpec`     | 默认补齐（归一化）与保存校验（biz 调用），规则目录与参数范围以此为准                                                       |
| `preprocessing/strategy/PreprocessStrategyParser`    | 快照 JSON → 策略对象（触发侧与控制面读取共用同一解析口径，失败回退内置默认）                                               |
| `preprocessing/strategy/PreprocessRuleConfig`        | 单条规则配置：`action`（三态）/ `enabled`（开关）/ `params`                                                                |
| `preprocessing/strategy/CustomRuleGroup`             | 自定义规则组：总开关 + 有序规则列表                                                                                        |
| `preprocessing/strategy/PreprocessCustomRule`        | 单条自定义规则：`pattern` / `action` / `replacement`                                                                       |
| `common/enums/preprocess/PreprocessRule`             | 七条固定规则的键与说明；三态规则与旧开关格式名单                                                                           |
| `common/enums/preprocess/PreprocessParam`            | 参数目录：归属规则、范围（数值参数的 min / max）、默认值、说明                                                             |
| `common/enums/preprocess/PreprocessAction`           | 三态动作：`KEEP` / `MARK` / `EXCLUDE`                                                                                      |
| `common/enums/preprocess/CustomRuleAction`           | 自定义动作：`REMOVE` / `REPLACE` / `EXTRACT`，含说明与替换文本约束                                                         |
| `common/enums/preprocess/ViewElementStatus`          | 处置状态字典（视图元素状态即它）                                                                                           |
| `common/enums/preprocess/PreprocessFieldType`        | 字段类型：金额 / 日期 / 面积 / 证书号                                                                                      |
| `common/domain/preprocess/`                          | 产物契约：`PreprocessView` / `ViewElement` / `ViewCell` / `NormalizedField` / `TraceEntry`                                 |
| `common/domain/rules/PreprocessViewRules`            | 剔除口径：`EXCLUDED_STATUSES` 与 `CHUNK_SKIP_STATUSES`                                                                     |
| `biz/task/runner/PreprocessTaskRunner`               | 领任务 → 读上游产物 → 调管线 → 写产物行与子步骤 → 回写终态（原子条件更新）                                                 |
| `biz/service/impl/PreprocessControlServiceImpl`      | 控制面：策略快照读取（归一化后下发）、详情与统计读取                                                                       |
| `biz/service/support/stats/PreprocessStatsSupport`   | 详情统计与结论文案（按产物现算，不落库）                                                                                   |
| `biz/service/support/assemble/PreprocessVoAssembler` | 产物 → 元素 / 轨迹 / 字段 / 单元格视图（纯映射，不查库）                                                                   |

## 待定项

只列**环节逻辑**尚未决定的问题；页面交互类在 `../../pages/预处理环节/README.md` 的待定项。

- **上游判据是否给用户可调**：页眉页脚判定在解析环节（`headerAreaRatio` / `runningTextMinPages`，页码判定还有两个代码常量），重复与噪声判定在组装环节（`noise-garbled-ratio` 等）；是否把这些阈值做成对应环节策略的可调项尚未决定，本环节只按标记处置。
- **重复档"标记"与"保留"缺样本验证**：三档改动后默认档（剔除）与原行为等效，但"标记 / 保留"两档在真实语料上的收益没有数据。
- **自定义规则上限（20 条）与单条正则长度（200 字符）是防滥用取值**：没有按真实策略样本标定过。
- **`PreprocessProperties` 的默认值没有落到 `application.yml`**：改目录阈值要走代码或 `application.yml` 同名键；是否落配置项尚未决定（与解析环节同款问题）。
- **产物里没有「视图 schema」字段**：卡片身份行只能显示策略版本，没有可展示的视图版本号；是否在视图产物契约里补一个版本号尚未决定。
- **`pageFrom` / `pageTo` 已算但未展示**：统计里的"受影响页范围"已在读取侧现算，页面还没有位置展示它。
- **剔除率没有警示阈值**：目前只报"剔除了多少"，没有"剔除率偏高"的判据。
- **「下游切片少掉多少」只能给元素数**：切片条数取决于切片策略（整表 / 行组等），当前口径给不了。
- **`preprocess-detail` 一次回全量元素、没有分页**：大文档的详情响应体偏大。
- **默认策略下「仅标记」是大头**：页眉页脚与目录默认只标注、仍进检索，卡片把它与"剔除"并列展示；是否要给更强的提示尚未决定。
- **视图不带标题层级**：派生视图元素没有 `level`，清洗后的正文标题只能按单档呈现。
- **策略页没有文档族**：`../../README.md` 的页面清单里「策略管理」仍是"待梳理"，预处理配置页（表格 + 行内展开的原文/结果对照）的口径暂时记在 `../../pages/预处理环节/`。

核对代码时读出的**注释与实现不一致、或行为未有定论**的点，记在这里待定（均以代码实现为准，文档按实现写）：

| 位置                           | 现象                                                                                 | 待定                             |
|--------------------------------|--------------------------------------------------------------------------------------|----------------------------------|
| `PreprocessRuleConfig.enabled` | 三态规则的旧开关字段：仅 `repeat` 在迁移期接受，其余三态规则给 `enabled` 直接判非法  | 迁移期结束后是否收掉该字段       |
| `PreprocessStrategy.action`    | 取值顺序为 `action` → 旧 `enabled` 换算 → 默认档，三处口径需要同改                   | 是否需要一次性数据迁移把快照改写 |
| `TraceEntry.ACTION_KEEP`       | 既表示"未命中改写"（文本整理），也表示"策略选了保留档"（三态规则），读取侧统一过滤掉 | 是否要给保留档单独的动作取值     |
| `PreprocessView.options`       | 扁平记录了七条规则的档位与参数，但没有记自定义规则组的总开关状态                     | 是否补 `custom` 键               |
