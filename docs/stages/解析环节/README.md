# 解析环节 · 功能逻辑

执行链第一环：把文件字节变成结构化产物（元素 + 页级指标 + 质量信息），交给组装环节。本目录记**环节内部的功能逻辑**（流程、口径、契约、阈值）；页面梳理（卡片四态、抽屉、原文预览、接口字段）在 `../../pages/解析环节/`，市面做法对照与出处见 `../../research/解析环节-开源方案调研.md`。

## 环节定位

| 项   | 值                                                                                                                         |
|------|----------------------------------------------------------------------------------------------------------------------------|
| 上游 | 文件中心（`wb_file_result` 的源文件与 MIME）；任务编排（biz 侧领取解析任务）                                               |
| 输入 | `ParseContext`（文件引用 + 输入流 + 解析阈值 + `fileResultId`）                                                            |
| 输出 | `ParseOutcome`（解析产物 `ParseResult` + 三个步骤日志 + 建议状态 + 单元数 / 失败单元数）                                   |
| 触发 | `KnowledgeFileParseController` 的解析触发接口 → 建任务 → `ParseTaskRunner` 领取并调用管线                                  |
| 下游 | 组装环节（消费 `ParseResult.sources`）                                                                                     |
| 落库 | 不在本环节内：`ParsePipeline` 是纯算法，不碰 DB 与对象存储，产物落库与任务状态回写由 biz 编排完成                          |
| 实现 | 规则路径：PDFBox（PDF）+ POI（DOC / DOCX / XLS / XLSX）；模型能力（OCR / 版面 / 表格结构）当前零实现                       |
| 代码 | `../../../workbuddy-worker/src/main/java/com/workbuddy/worker/parser/`（阈值在 `ParseProperties`，前缀 `knowledge.parse`） |

## 文档导航

| 文档                                      | 管什么                                                                                                | 什么时候看                   |
|-------------------------------------------|-------------------------------------------------------------------------------------------------------|------------------------------|
| `01-功能逻辑.md`                          | 路由与五步流程、PDF 三段实现、Office 三格式对照、判定单元与指标、信号与降级、成功门槛、阈值、产物契约 | 改解析行为、排查解析结果异常 |
| `02-流程图-环节总览.html`                 | 触发 → 任务 → 五步管线 → 落库的主链（可交互，节点带代码溯源）                                         | 理清环节整体走向             |
| `03-流程图-PDF解析器步骤.html`            | PDF 三段内部步骤：提取 / 识别 / 装配与各段产出                                                        | 改 PDF 解析逻辑              |
| `04-流程图-Office解析器步骤.html`         | 骨架 + DOCX / DOC / Excel 三条链的遍历与产出                                                          | 改 Office 解析逻辑           |
| `05-待办清单.md`                          | 逐条待办：问题、现状证据、解决什么、出处、状态与提交号                                                | 排期、逐条开工               |
| `../../pages/解析环节/README.md`          | 卡片与抽屉四态、原文预览、五个接口的请求响应、后端统计汇总                                            | 改页面、联调接口             |
| `../../research/解析环节-开源方案调研.md` | 市面方案对照（可直接照搬 / 需改造 / 不适用）与出处                                                    | 评估新能力、找口径依据       |

三张流程图是 `archify` 生成的单文件 HTML（内联 SVG，浏览器直接打开，可缩放与切换明暗主题、导出 PNG / SVG），节点上的溯源指向 `meta.repository` 里固定的提交。生成源（`candidate.json`，可重跑）与门禁回执（`finalize` / `finalize-summary` / `browser-check` / `delivery`）在同名 `.json` 文件里，放在 `图源/`。

通用口径（响应体形状、错误码分段、鉴权、判空约定）见 `../../README.md` 的「全局约定」章，本目录不复述。

## 代码索引

| 位置                              | 职责                                                                                                               |
|-----------------------------------|--------------------------------------------------------------------------------------------------------------------|
| `parser/ParsePipeline`            | 环节编排：文件级路由 → 原生解析 → 信号判定与降级处置 → 结果封装 → 成功占比门槛评估                                 |
| `parser/DocumentParserPort`       | 解析器契约：`supports(mimeType)` / `capabilityName` / `capabilityVersion` / `parse`                                |
| `parser/ParseContext`             | 解析输入：文件引用、输入流、解析阈值、`fileResultId`                                                               |
| `parser/ParseProperties`          | 全部解析阈值（`knowledge.parse` 前缀）                                                                             |
| `parser/pdf/PdfBoxDocumentParser` | PDF 编排（提取 → 页眉页脚识别 → 逐页装配）                                                                         |
| `parser/pdf/extract/`             | PDF 提取段：`PdfPageExtractor`（字符 → 行 → 阅读顺序 → 页级指标）、`LineTexts`、`PdfGeometry`                      |
| `parser/pdf/detect/`              | PDF 识别段：`HeaderFooterDetector`、`TableCandidateDetector`、`RuleLines`（线段与线网格）                          |
| `parser/pdf/assemble/`            | PDF 装配段：`PdfBodyAssembler`、`TextTableBuilder`（无边框表）、`RuledTableBuilder`（有边框表）、`PdfImageEmitter` |
| `parser/pdf/model/`               | PDF 页内事实模型：`CharInfo`、`Token`、`PageLine`、`PageContent`、`Segment`、`Region`、`Grid`                      |
| `parser/pdf/layout/`              | PDF 分栏与阅读顺序：`PageLayoutAnalyzer` 端口 + `ProjectionPageLayoutAnalyzer` 投影法实现                          |
| `parser/office/`                  | Office 抽象骨架 `AbstractPoiDocumentParser` + `office/impl/` 三格式实现                                            |
| `parser/signal/`                  | 信号判定 `SignalDetector`（实现 `signal/impl/SignalDetectorImpl`）与降级处置 `signal/fallback/`                    |
| `parser/capability/`              | 能力预留：`CapabilityRegistry` 与版面 / 表格 / OCR 三个 provider（零实现）                                         |
| `parser/support/`                 | 跨格式共性：`ParserStreamSupport`（输入流全量读入）、`TocLineFeature`（目录行特征）                                |

## 待定项

只列**环节逻辑**尚未决定的问题；页面交互与原文预览类在 `../../pages/解析环节/README.md` 的待定项。逐条开工顺序、现状证据与状态跟踪见 `05-待办清单.md`，本节只留口径描述。

- **PPT / PPTX 未接入解析**：`FileFormat` 已登记两种格式，没有对应解析器（未进启用白名单）；接入后是否按 slide 页单位折算判定单元尚未决定。
- **Word 虚拟单元与真实页不等价**：DOC / DOCX 按字符折算判定单元，页码与真实排版页对不上（OOXML 不存分页）；预览定位是否改用段落序号尚未决定。
- **`CapabilitySnapshot` 能力项恒空**：产物字段、血缘与前端展示都已就位，解析器只写 `parserName` / `parserVersion`，`ocr` / `layout` / `table` 三项无人写入 —— **取值口径已定**：三项为 `null` 即「未接入该能力」（`CapabilitySnapshot` 类注释即契约），能力接入后由 provider 回填。
- **Excel 公式的取值取向**：当前先求值、拿不到才回落文件里的缓存值，`NOW()` / `RAND()` 这类易变函数给出的是重新计算值而非保存时的显示值；是否改为缓存值优先尚未决定。
- **`.xls` 公式的缓存值无法辨别**：HSSF 的公式记录总带一个数值缓存（从未计算过就是 0），与真实结果区分不开；是否对旧格式一律求值尚未决定。

核对代码时读出的**注释与实现不一致、或行为未有定论**的点，记在这里待定（均以代码实现为准，文档按实现写）：

| 位置                                         | 现象                                                                     | 待定                 |
|----------------------------------------------|--------------------------------------------------------------------------|----------------------|
| 降级告警文案                                 | 按「第 {region} …」拼接，region 自带 `page ` 前缀，渲染成「第 page 3 …」 | 是否统一 region 形态 |
| Office 的 `textAreaRatio` / `imageAreaRatio` | 恒 0，「文字占比低」这条疑似图片判据对 Office 永不成立                   | 是否给 Office 补口径 |
| Word 的图片归属                              | 全档图片数全记在首个单元，不按位置归属                                   | 是否按位置归属       |
| Word 的单元字符预算                          | 表格单元格文本与页眉页脚文本不进预算                                     | 是否纳入             |
| Excel 的 `isHeader` 与 `headerRow`           | 数据首行不在第 0 行时，两者不自洽                                        | 取值口径             |
| Excel 的 `cols`                              | 取首行 `getLastCellNum`，未判该行是否为 null                             | 是否补防护           |
| 合并区被覆盖格 / `vMerge=continue` 格        | 不产元素，其文本不进产物                                                 | 是否补文本           |
| DOC 表格 `cols`、DOCX 与 DOC 的表头          | `cols` 未设置；`headerRow` 恒 0，解析环节没有表头判定                    | 是否补口径           |
| DOC 段落、DOC 与 Excel 的页眉页脚            | DOC 段落无 `style`、无 `tocCandidate`；DOC 与 Excel 不读页眉页脚         | 是否补齐             |
| Excel 图片                                   | 不产事实、不置 `needsOcr`，图片文字未识别在信号层没有表示                | 是否补               |
