# 解析环节

执行链页（`/knowledge/:id/stages`）上的**解析**环节：单张解析卡片的四态、触发解析、以及「详情」抽屉里的解析结果与原文预览。

| 项         | 值                                                                                                                                                                      |
|------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 路由       | `/knowledge/:id/stages`（与其余环节共用同一路由，按执行链节点区分）                                                                                                |
| 入口       | 知识库列表 → 某库的「切片阶段（执行链）」→ 解析环节卡片                                                                                                                 |
| 前端视图   | `../../../web/src/views/knowledge-base/PipelineStageView.vue`（页面）                                                                                                   |
| 前端组件   | `../../../web/src/components/pipeline/ChainNode.vue`（卡片）、`StageDetailDrawer.vue`（抽屉）                                                                           |
| 前端接口层 | `../../../web/src/api/pipeline.ts`                                                                                                                                      |
| 后端       | `KnowledgeFileParseController`（解析触发 / 解析详情）、`KnowledgeFileStageController`（产物内容）、`LineageController`（血缘）                                          |
| 涉及接口   | `GET /file-results/{id}/lineage`、`POST /file-results/{id}/parse`、`GET /file-results/{id}/parse-detail`、`GET /file-results/{id}/stage-content`、`GET /files/{fileId}` |

链图的画布、触发弹窗、图例与轮询属执行链壳，归 `../知识库列表/README.md` 与本文档的卡片条目共同覆盖；其余四个环节（组装 / 预处理 / 切片 / 向量化）共用同一套卡片与抽屉结构，本文档只写解析环节特有的部分。

## 文档导航

| 文档             | 管什么                                                    | 什么时候看           |
|------------------|-----------------------------------------------------------|----------------------|
| `01-页面功能.md` | 卡片六行四态、触发与分叉、抽屉四个分区、原文预览与联动    | 改页面、调交互       |
| `02-接口交互.md` | 五个接口的时机 / 请求 / 响应字段 / 错误码                 | 前端联调、改接口     |
| `03-接口逻辑.md` | 触发分支、统计汇总、分页与过滤、归属校验、Controller 划分 | 改后端、排查线上问题 |
| `04-自测清单.md` | 改完要手工验哪些点、期望结果                              | 每次交付前           |

通用口径（响应体形状、错误码分段、鉴权、分页参数）见 `../../README.md` 的「全局约定」章，本页不复述。

## 待定项

- **原文预览的 Excel「区域」高亮粒度**：解析详情已能看到整张表的格内文本（`extra.cells` 渲染成网格，内容不截断）；**单元格级定位仍未做** —— 产物里 `TABLE_CELL` 是单格、`TABLE` 是整表，且单元格不作为条目下发，点某格只能定位到整表。是否把单元格展开成条目（按合并跨度画整块区域 / 点表格元素画整表框）尚未决定。
- **Word 段落序号的对齐口径**：锚点用「解析产物只统计非空段落」这一口径推序号，渲染结果里空段落也在，序号可能错位。当前用样式名先收窄候选再取序号；是否改成按文本比对兜底，尚未决定。
- **`.doc` / `.xls` 无浏览器端渲染器**，当前只给文件信息与下载。是否上服务端转换，尚未决定。
- **Excel 大表的渲染上限**：当前单表 200 行 × 60 列，超出提示已截断。上限取值尚未定。
- **关键字过滤只作用于当前页**（后端按页给内容）。是否要跨页搜索（后端加 `keyword` 参数）尚未决定。
- **原文预览的「适应宽度」档默认关闭**，且开关状态不持久化。是否默认开启、是否连开关一起记，尚未决定。
- **分隔条比例的存储粒度**：当前按「文件结果 + 任务」分键，是否改为全局单值（所有文件共用一份栏宽偏好）尚未决定。
- **索引页与评测页按 `knowledgeBaseId` 的接口尚未做归属校验**（见 `../知识库列表/README.md` 的待定项）。
- **`CapabilitySnapshot.table` 无人写入**：产物字段、血缘 VO 与前端展示都已就位，三个能力项（`ocr` / `layout` / `table`）恒为空 —— **取值口径已定**：`null` 即「未接入该能力」（`CapabilitySnapshot` 类注释即契约），不额外补「未接入」值，能力接入后由 provider 回填。
- **`ParseElement.headerRepeated` 无赋值点**：字段与下游读取（组装环节续表判定）都在，字段注释已标明当前无解析器赋值；是否补写或从契约中移除，需与组装环节的续表判定一起定。
- **解析环节的模型能力已接入，与规则路径并存**：能力访问层已按 D33 拆分归位 —— HTTP 访问、连接与凭据上移平台（`knowledge.model.vendors.*`），响应到领域结构的映射留在解析侧的三个实现（`VisionTableStructureProvider` / `VisionOcrProvider` / `VisionLayoutProvider` 配 `capability/prompt/CapabilityPrompts` 与 `capability/CapabilityRequests`），统一经 `ModelGatewayPort.visionByCapability` 走平台出口。三项开关（`knowledge.parse.capability.ocr-enabled` / `layout-enabled` / `table-enabled`，默认全关）打开时按能力选型调平台，关闭时仍走规则路径（`TableCandidateDetector` / `RuleLines` / `ProjectionPageLayoutAnalyzer`）。**真实链路上的图像字节来源仍缺**：页面与区域栅格化、图像落对象存储的键约定、按页或区域取回字节登记在 `MODEL-14`，接通前视觉入参没有图像数据。
- **分栏能力只到端口、没有实现选择**：`PageLayoutAnalyzer` 当前只有投影法规则实现，没有按开关切换实现的能力；是否补 `PageLayoutRegistry`（与 `CapabilityRegistry` / `StructureJudgeRegistry` 同范式）尚未决定。PDFBox 自带的文章线索（beads，仅当文件声明时存在）是否接入也尚未决定。
- **解析阈值的唯一落点是配置文件**：`ParseProperties` 的阈值（行容差、页级判定、表格四条、分栏六条、扫描页图片占比、Office 单元折算、图片元素面积门槛）全量写在 `knowledge-biz/src/main/resources/config/knowledge-parse.yml`（解析环节配置只此一处，由主文件 `application.yml` 的 `spring.config.import` 清单载入），逐键与 `ParseProperties` 同名 kebab-case，默认值与代码默认值一致（`ParsePropertiesConfigTest` 对账）；改阈值改配置即可，代码侧只留兜底默认值。
- **图片未识别告警逐张产**：`OCR_IMAGE` 事实按图片逐张产（Office 与 PDF 同口径），多图文档的告警条数会很多；是否按页 / 按文档聚合尚未决定。
- **PPT / PPTX 未接入解析**：`FileFormat` 已登记两种格式，但没有对应解析器（未进启用白名单）；接入后是否按 Azure 的 slide 页单位口径折算单元尚未决定。
- **Word 虚拟单元与真实页不等价**：DOC / DOCX 按 3000 字符折算判定单元，页码与真实排版页对不上（OOXML 不存分页）；预览定位是否改用「段落序号」而非页码尚未决定。
- **可打印 ASCII 乱码是盲区**：乱码判据只认替换字符 / 私用区 / 未分配码点，字体缺 ToUnicode 时错位出的 `ÿþ`、`¤` 这类合法码点会漏判；是否补「文档级字符集偏离」口径尚未决定。
- **空白页与纯矢量页不区分**：无文本也无图片的页面一律按空白页处理（矢量图表页也在内）；是否用矢量覆盖再细分尚未决定。
- **小文档门槛偏严**：1~3 页文档只要有 1 页不可读就落到 `RATIO_BELOW_THRESHOLD`（90% 比例在小分母上等价零容错）；是否对短文档单独放宽尚未决定。
- **Excel 公式的取值取向**：当前先求值、拿不到才回落文件里的缓存值，于是 `NOW()` / `RAND()` 这类易变函数给出的是重新计算值而非保存时的显示值；是否改为缓存值优先尚未决定。
- **`.xls` 公式的缓存值无法辨别**：HSSF 的公式记录总带一个数值缓存（从未计算过就是 0），与真实结果区分不开；是否对旧格式一律求值尚未决定。
