# 知识库列表

知识库的**唯一入口与总览**：看有哪些库、各自绑了什么策略、发布到哪个索引版本，并从这里进入单个库的处理链、索引发布与检索评测。同时承担建库、改库、启停、删除与文档导入。

| 项         | 值                                                                                                                                                                                                                                                                                                                                                                                          |
|------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 路由       | `/knowledge/manager`（`meta` 无限制，登录即可访问）；本库的三个下钻页路由是 `/knowledge/:id/stages`、`/knowledge/:id/index`、`/knowledge/:id/retrieval`                                                                                                                                                                                                                                  |
| 入口       | 侧栏一级「知识库」下的二级项「知识库管理」；首页动作卡会带 `?action=create` / `?action=import` 跳进来                                                                                                                                                                                                                                                                                       |
| 前端视图   | `../../../web/src/views/knowledge-base/KnowledgeBaseView.vue`（页面）与 `../../../web/src/components/knowledge-base/KnowledgeBaseCard.vue`（单卡）                                                                                                                                                                                                                                          |
| 前端类型   | `../../../web/src/types/knowledge-base.ts`                                                                                                                                                                                                                                                                                                                                                  |
| 前端接口层 | `../../../web/src/api/knowledge-base.ts`、`../../../web/src/api/strategy.ts`（策略绑定）、`../../../web/src/api/file.ts`（导入）                                                                                                                                                                                                                                                            |
| 后端       | `workbuddy-biz` 模块的 `KnowledgeBaseController` / `KnowledgeBaseServiceImpl` / `KnowledgeBaseDbServiceImpl`（文档提交与文件结果的业务编排同在 `KnowledgeBaseServiceImpl`）                                                                                                                                                                                                                 |
| 涉及接口   | `GET /knowledge-base/page`、`GET /knowledge-base/stats`、`GET /knowledge-base/{id}`、`POST /knowledge-base`、`PUT /knowledge-base/{id}`、`POST /knowledge-base/{id}/disable`、`POST /knowledge-base/{id}/enable`、`DELETE /knowledge-base/{id}`、`PUT /knowledge-base/{id}/strategy-bindings`、`GET /strategy-versions`，以及导入弹窗的 `POST /files` 与 `POST /knowledge-base/{id}/submit` |
| 涉及的表   | `kb_knowledge_base`、`kb_strategy_binding`、`kb_file_result`、`kb_index_set`、`kb_index_version`、`kb_audit_log`（写审计）                                                                                                                                                                                                                                                                  |

## 可见范围：每个人只看到自己创建的知识库

口径由 `KnowledgeBaseRules.visibleOwnerId` / `KnowledgeBaseRules.checkAccessible` 一处定义，列表、计数、详情、改删、启停、策略绑定与文件提交全部走它：

| 角色     | 能看到             | 能改/停用/删除/绑定 |
|----------|--------------------|---------------------|
| 普通用户 | **只有自己创建的** | 只有自己的          |
| 管理员   | 全部               | 全部                |

三条容易踩的细则：

- **归属在服务端定**：接口不接受"归属/创建人"参数，前端传谁就查谁等于没做隔离。前端只负责显示后端给的那一页。
- **越权按「不存在」处理**：访问别人的库报 `40401 知识库不存在`，不是 `40104 权限不足`。后者等于确认"这个库存在，只是不是你的"，会把别人的库 ID 变成可枚举、可探测的信息。
- **知识库必须带归属**：`create` 在当前用户为空时直接拒绝（`40101`），不静默落 `user_id = NULL`。存量 `user_id` 为空的早期数据**不视为公共库**，普通用户看不到（管理员可见）。

「看不到」也包含**不能往里写**：`KnowledgeBaseServiceImpl.submit` 在建档前校验归属，拿着别人的 ID 也提交不进文档。

## 本页读接口的范围

列表页回答**跨库的横向问题**（哪个库绑了什么策略、发布到 v几、有多少文档），环节页回答**单库纵向问题**（这个库的文件走到哪一步了）。本页自持 `page` / `stats` 两个读接口：一次翻页回填策略摘要、文档数、已发布索引版本（都是批量查询，与页大小无关）；环节页按单库展开任务与产物。

**同一个 Controller 里还有两条不属于本页的接口**：`GET /knowledge-base/{id}/submit-logs` 与 `GET /knowledge-base/{id}/file-results`（切片阶段页的列表数据源）—— 它们与「提交文件」一样，归属、启用状态与可见范围都由知识库决定，按域放在 `KnowledgeBaseController` 里；但**文档归属按使用方**，这两条记在切片阶段页的文档里（见 `docs/README.md` 页面索引 04）。本页只用其中的 `POST /knowledge-base/{id}/submit`（导入弹窗建档）。

## 与其他页面的关系

- 卡片点击进入[切片阶段（执行链）](../../README.md)页；「索引与发布」「评测」分别进对应页 —— 三页都按 `:id` 定位单个库，**入口只在本页**（它们是本页的下钻，不是并列导航）。
- 「导入文档」弹窗归本页：首页的动作卡只是带 `?action=import` 跳过来，真正实现（含候选库列表）在本页组件里。
- 顶部的「文档数」与[首页](../首页/README.md)资产带的「文档」**同源同口径**（`kb_file_result` 行数，且都按同一可见范围过滤）。
- 策略绑定写入 `kb_strategy_binding`，卡片上的三行策略摘要就来自它；策略的增删改在[策略管理](../../README.md)页。

## 文档导航

| 文档             | 管什么                                         | 什么时候看           |
|------------------|------------------------------------------------|----------------------|
| `01-页面功能.md` | 页面构成、卡片与操作、筛选/分页/空态、启停语义 | 改页面、调交互       |
| `02-接口交互.md` | 十个接口的时机 / 请求 / 响应字段 / 错误码      | 前端联调、改接口     |
| `03-接口逻辑.md` | 各接口的后端步骤、可见范围实现与涉及的表       | 改后端、排查线上问题 |
| `04-自测清单.md` | 改完要手工验哪些点、期望结果                   | 每次交付前           |

通用口径（响应体形状、错误码分段、分页参数、代理前缀、鉴权）见 `../../README.md` 的「全局约定」章，本页不复述。

## 待定项

- **归属校验已覆盖：本页、提交入口、环节页全部按 `fileResultId` 直取的 12 个接口**。环节页那批（解析触发/解析详情/产物内容/血缘，以及组装/预处理/切片/向量化各自的触发与详情）由 `workbuddy-biz` 的 `FileResultAccessGuard` 在**服务层入口**统一校验：按 `kb_file_result.knowledge_base_id` 反查知识库，再走 `KnowledgeBaseRules.checkAccessible`，越权与不存在同样返回 `KB_NOT_FOUND`（40401），未登录 40101；口径见 `../解析环节/03-接口逻辑.md`。**仍未逐个审的是索引页与评测页**：它们按 `knowledgeBaseId` 访问（`IndexSetServiceImpl`、`RetrievalController` 那批），目前只做存在性判断（`getById` 判空），没有归属校验，拿别人的库 ID 仍可访问；收口要逐接口加校验，属独立一轮。
- **批量查绑定未按归属过滤**：`GET /knowledge-base/strategy-bindings` 返回的是全平台绑定行，响应里只有 `knowledgeBaseId` 与策略版本信息（**不含库名与内容**），前端只与自己可见的库求交集。要彻底收口需再查一次可见库 ID，收益有限，先记在这里。
- **`page` 的 `size` 没有上限**：导入弹窗按 `size=500` 一次拉全库；加限制时必须 ≥500。
- **筛选条件不持久**：刷新或离开再回来会重置为「已启用 + 默认排序」。要做可考虑写进 URL（可分享、可后退），与处理链页那种"折叠状态存 localStorage"不是一类。
- **卡片操作区已放六个入口**（导入/编辑/索引与发布/评测/停用/删除）：380px 列宽下刚好放得下，再加就要考虑收进「更多」菜单。
- **种子数据的清理**：默认知识库与演示库的 `user_id` 为空（早期假数据），现在只有管理员看得到。是否迁移归属或清理，随种子数据一起决定。
