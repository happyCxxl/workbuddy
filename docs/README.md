# 工作台 · 页面梳理与全局约定

本文档是 `docs/` 的**唯一根文档**，承担两件事：说明页面文档怎么组织（含索引与维护约定）、记录跨所有页面的全局口径。页面独有的内容一律放在各自页面目录里，这里不复述。

`docs/` 下有四类平行文档，按视角分开放：

| 目录        | 视角                                                 | 一个单元是什么                                    |
|-------------|------------------------------------------------------|---------------------------------------------------|
| `pages/`    | 页面梳理：界面能做什么、调哪个接口、怎么验           | 一个功能单元（页面 / 路由），见下表页面索引       |
| `stages/`   | 环节功能逻辑：管线内部怎么算、产出什么               | 一个执行链环节（解析 / 组装 / 预处理 / 切片 / …） |
| `research/` | 市面方案对照与出处                                   | 一份调研主题                                      |
| `design/`   | 方案设计与取舍：现状、目标态、备选方案对比与落地路径 | 一个议题                                          |

环节文档只写环节内部的口径（流程、判定、契约、阈值），接口字段与页面交互留在 `pages/` 侧；两份文档互相引用，不复述同一段内容。

## 页面文档怎么组织

按**功能单元**记录平台每个页面：一个功能单元一个目录，目录下按内容分工拆成多份文档：

| 文档             | 回答什么                                                       |
|------------------|----------------------------------------------------------------|
| `README.md`      | 这页是什么：定位、路由与视图、涉及接口、文档导航、待定项       |
| `01-页面功能.md` | 给谁用、界面上能做什么、交互与状态                             |
| `02-接口交互.md` | 前端在什么时机调哪个接口、请求与响应字段、错误码               |
| `03-接口逻辑.md` | 后端拿到请求之后做了什么（校验、落表、副作用、边界）与涉及的表 |
| `04-自测清单.md` | 改完之后手工要验哪些点、期望结果                               |

目标是：只看文档就能回答「这个按钮点下去，数据是怎么走到库里的」，不必回头翻代码。

## 维护约定

- **代码是实况，文档跟着代码追。** 页面扩功能、改交互、换接口之后招呼一声更新对应文件，不靠人肉记得「改了代码要同步文档」。
- **不写行号**，只写 `文件:方法名`。行号一动功能就失效，符号名才是稳定的。
- **跨页面的同一份口子只在本文档写一次**，页面文件只写本页特有的东西，遇到通用规则引用而不复述。
- **页内按归属规则分文档**：接口路径 / 请求体 / 响应字段 / 错误码 → `02-接口交互.md`；校验顺序 / 落库 / 副作用 / 边界条件 → `03-接口逻辑.md`；两边都受影响时各写一句并互相引用，同样不复述。
- **类型定义不抄全文**，只写契约要点与**与后端不一致的地方**。
- **页面 `README.md` 只记现状**：定位、路由与视图、涉及接口、文档导航、待定项；不记变更时间线，也不记决策过程与理由。「待定项」写的是尚未决定的问题，不是沿革。
- **文档按需建**：某份分文档没有内容就不建，不凑齐 5 份写空文件；**接口归属按「谁在用它」划分**——挂在布局壳上的弹窗，接口就归壳，不归接口所在的后端模块。
- 页面 `README.md` 里的「待定项」是梳理过程中读出来的口径问题，留给迭代时顺手决定，不作为结论。

## 页面索引

| 序   | 功能单元                                            | 路由                       | 前端视图                                                | 对应后端 Controller（初判）                                                                                                                                                                                                                                                                                         | 状态   |
|------|-----------------------------------------------------|----------------------------|---------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------|
| 壳   | 布局壳（菜单 / 返回 / 退出登录 / 账号区入口）       | 无独立路由                 | `../web/src/layouts/AppLayout.vue`                      | —                                                                                                                                                                                                                                                                                                                   | 已梳理 |
| 01   | 认证（登录 / 注册）                                 | `/login`                   | `../web/src/views/login/LoginView.vue`                  | `AuthController`                                                                                                                                                                                                                                                                                                    | 已梳理 |
| 02   | 首页                                                | `/home`                    | `../web/src/views/home/HomeView.vue`                    | `KnowledgeHomeController`                                                                                                                                                                                                                                                                                           | 已梳理 |
| 03   | 知识库列表                                          | `/knowledge/manager`       | `../web/src/views/knowledge-base/KnowledgeBaseView.vue` | `KnowledgeBaseController`（含文档提交 / 提交记录 / 文件结果三条接口）                                                                                                                                                                                                                                               | 已梳理 |
| 04-0 | 执行链壳（链图 / 触发弹窗 / 图例 / 轮询）           | `/knowledge/:id/stages`    | `../web/src/views/knowledge-base/PipelineStageView.vue` | `KnowledgeLineageController`（血缘 `GET /file-results/{id}/lineage`）、`KnowledgeFileStructureController`、`KnowledgeFilePreprocessController`、`KnowledgeFileChunkController`、`KnowledgeFileEmbedController`、`FileController`（列表数据源 `/knowledge-base/{id}/file-results` 已并入 `KnowledgeBaseController`） | 待梳理 |
| 04-1 | 解析环节（卡片 / 触发 / 详情抽屉 / 原文预览）       | `/knowledge/:id/stages`    | `../web/src/views/knowledge-base/PipelineStageView.vue` | `KnowledgeFileParseController`（解析触发 `POST /{fileResultId}/parse`、解析详情 `GET /{fileResultId}/parse-detail`）、`KnowledgeFileStageController`（各环节共用的产物内容 `GET /{fileResultId}/stage-content`）、`FileController`（原文件字节 `GET /files/{fileId}`）                                              | 已梳理 |
| 04-2 | 组装环节（卡片 / 触发 / 详情抽屉 / 组装文档）       | `/knowledge/:id/stages`    | `../web/src/views/knowledge-base/PipelineStageView.vue` | `KnowledgeFileStructureController`（组装触发 `POST /{fileResultId}/structure`、组装详情 `GET /{fileResultId}/structure-detail`）、`KnowledgeFileStageController`（各环节共用的产物内容 `GET /{fileResultId}/stage-content`）                                                                                        | 已梳理 |
| 04-3 | 预处理环节（卡片 / 触发 / 详情抽屉 / 清洗后的正文） | `/knowledge/:id/stages`    | `../web/src/views/knowledge-base/PipelineStageView.vue` | `KnowledgeFilePreprocessController`（预处理触发 `POST /{fileResultId}/preprocess`、预处理详情 `GET /{fileResultId}/preprocess-detail`）、`KnowledgeFileStageController`（各环节共用的产物内容 `GET /{fileResultId}/stage-content`，支持 `status` 过滤）                                                             | 已梳理 |
| 04-4 | 切片环节（卡片 / 触发 / 详情抽屉 / 切片结果）       | `/knowledge/:id/stages`    | `../web/src/views/knowledge-base/PipelineStageView.vue` | `KnowledgeFileChunkController`（切片触发 `POST /{fileResultId}/chunk`、切片详情 `GET /{fileResultId}/chunk-detail`）、`KnowledgeFileStageController`（各环节共用的产物内容 `GET /{fileResultId}/stage-content`，支持按类型 / 兜底 / 父子过滤）                                                                      | 已梳理 |
| 05   | 索引与发布                                          | `/knowledge/:id/index`     | `../web/src/views/knowledge-base/IndexBuildView.vue`    | `KnowledgeFileIndexController`                                                                                                                                                                                                                                                                                      | 待梳理 |
| 06   | 检索评测                                            | `/knowledge/:id/retrieval` | `../web/src/views/knowledge-base/RetrievalEvalView.vue` | `KnowledgeRetrievalController`                                                                                                                                                                                                                                                                                      | 待梳理 |
| 07   | 策略管理                                            | `/knowledge/strategy`      | `../web/src/views/strategy/StrategyManagementView.vue`  | `KnowledgeStrategyController`                                                                                                                                                                                                                                                                                       | 待梳理 |
| 08   | 用户管理（仅管理员）                                | `/user`                    | `../web/src/views/user/UserManagementView.vue`          | `UserController`                                                                                                                                                                                                                                                                                                    | 待梳理 |
| 09   | 个人中心                                            | `/profile`                 | `../web/src/views/profile/ProfileView.vue`              | `UserController`（个人信息与改密，仅本人）                                                                                                                                                                                                                                                                          | 已梳理 |
| 10   | 系统设置（所有用户可见，卡片按权限）                | `/settings`                | `../web/src/views/system/SystemSettingView.vue`         | `StorageSourceController`（存储数据源：列举存储源并启用）                                                                                                                                                                                                                                                           | 已梳理 |
| 11-0 | 模型清单（模型目录维护 / 新增 / 编辑 / 启停）       | `/model/manager`           | `../web/src/views/model/ModelListView.vue`              | `ModelController`（模型清单 / 新增 / 编辑 / 启停四条）                                                                                                                                                                                                                                                              | 已梳理 |
| 11-1 | 用量与成本（调用概览与明细，只读）                  | `/model/usage`             | `../web/src/views/model/ModelUsageView.vue`             | `ModelUsageController`（用量概览与调用明细）                                                                                                                                                                                                                                                                        | 已梳理 |

顺序按用户使用动线排。一个路由里若装了互相独立的功能，会拆成多份文件；「Controller（初判）」是文件归属的初步判断，逐页梳理时以实际调用为准，可能修正。

## 文档入口

- 壳 布局壳（菜单 / 返回 / 退出登录 / 账号区入口）→ `pages/布局壳/README.md`
- 01 认证（登录 / 注册）→ `pages/登录认证/README.md`
- 02 首页 → `pages/首页/README.md`
- 03 知识库列表 → `pages/知识库列表/README.md`
- 04-1 解析环节 → `pages/解析环节/README.md`
- 04-2 组装环节 → `pages/组装环节/README.md`
- 04-3 预处理环节 → `pages/预处理环节/README.md`
- 04-4 切片环节 → `pages/切片环节/README.md`
- 09 个人中心 → `pages/个人中心/README.md`
- 10 系统设置 → `pages/系统设置/README.md`
- 11 模型管理 → `pages/模型管理/README.md`
- 其余功能单元（04-0、05~08）梳理后在此补上

## 全局约定

跨所有页面的同一份口径。页面文件只引用本章，不复述其中的内容。

### 一、响应体与 HTTP 状态码

后端统一响应体 `R<T>`（`../workbuddy-common/src/main/java/com/workbuddy/common/dto/response/R.java`）：

| 字段        | 含义                                 |
|-------------|--------------------------------------|
| `code`      | 0 表示成功；**错误语义全部由它表达** |
| `msg`       | 提示语，成功的默认值是「操作成功」   |
| `data`      | 业务数据，失败时为 `null`            |
| `timestamp` | 毫秒时间戳                           |

- **HTTP 状态码恒为 200**（网络层故障除外）。业务错误、参数错误、未认证、无权限一律走 200 + `R.code`。浏览器会把裸 4xx 响应渲染成 `HTTP ERROR 403` 页面，前端也拿不到可判断的 code。
- 三处出口共同保证这个口径：`GlobalExceptionHandler`（Controller 抛出的异常）、`RestAuthenticationEntryPoint`（未登录）、`RestAccessDeniedHandler`（已登录但无权限）。后两者位于 `workbuddy-auth` 的 `config` 包，发生在 Controller 之前，不经过全局异常处理器。
- 前端对应类型是 `../web/src/types/response.ts` 的 `R<T>`，分页数据对应 `PageResult<T>`。
- `R.failed(msg)`（`code=1`）是通用失败码，**现有代码无人调用**；业务失败一律抛 `KnowledgeException`。

### 二、错误码

登记在 `../workbuddy-common/src/main/java/com/workbuddy/common/error/ErrorCode.java`，**新错误码必须在此登记**。分段：`40001` 参数、`401xx` 认证、`402xx` 限流、`404xx` 业务、`40500` 系统兜底。下表「默认提示语」列为源码中的原文，前端未给自定义消息时展示它。

| code  | 常量                                   | 默认提示语                                           |
|-------|----------------------------------------|------------------------------------------------------|
| 40001 | `PARAM_INVALID`                        | 参数错误                                             |
| 40101 | `UNAUTHORIZED`                         | 未认证或令牌无效                                     |
| 40102 | `USERNAME_EXISTS`                      | 用户名已存在                                         |
| 40103 | `LOGIN_FAILED`                         | 用户名或密码错误                                     |
| 40104 | `FORBIDDEN`                            | 权限不足，无法访问该功能                             |
| 40105 | `USER_NOT_FOUND`                       | 用户不存在                                           |
| 40106 | `ROLE_INVALID`                         | 角色不合法                                           |
| 40107 | `USER_SELF_OPERATION_FORBIDDEN`        | 不能对当前登录账号执行该操作                         |
| 40108 | `LAST_ADMIN_FORBIDDEN`                 | 系统需保留至少一个启用的管理员账号                   |
| 40109 | `USER_STATUS_INVALID`                  | 用户状态不合法                                       |
| 40110 | `OLD_PASSWORD_MISMATCH`                | 当前密码不正确                                       |
| 40111 | `LOGIN_TOO_FREQUENT`                   | 登录尝试过于频繁，请稍后再试                         |
| 40201 | `RATE_LIMITED`                         | 请求过于频繁，请稍后再试                             |
| 40401 | `KB_NOT_FOUND`                         | 知识库不存在                                         |
| 40402 | `KB_STATUS_ILLEGAL`                    | 知识库状态不合法                                     |
| 40410 | `FILE_NOT_FOUND`                       | 文件不存在                                           |
| 40420 | `REQUEST_ID_MISSING`                   | 缺少幂等键                                           |
| 40421 | `KB_NOT_ACTIVE`                        | 知识库未启用，不可提交文档                           |
| 40431 | `TASK_ALREADY_PENDING`                 | 任务进行中，请勿重复触发                             |
| 40432 | `FILE_RESULT_NOT_FOUND`                | 文件结果不存在                                       |
| 40433 | `STRATEGY_VERSION_NOT_FOUND`           | 策略版本不存在或未启用                               |
| 40435 | `EMBED_MODEL_INCOMPATIBLE`             | 切片最大片长超过模型窗口，请更换模型或调整切片策略   |
| 40436 | `TASK_UPSTREAM_MISSING`                | 未指定上游产物，请从上游产物的卡片发起触发           |
| 40438 | `TASK_STRATEGY_MISSING`                | 未解析到可用策略，请绑定策略或显式选择后触发         |
| 40439 | `TASK_UPSTREAM_NOT_READY`              | 上游产物来自未成功的运行，请重新触发上游环节         |
| 40441 | `INDEX_VERSION_NOT_FOUND`              | 索引版本不存在                                       |
| 40442 | `INDEX_ONLINE_DELETE_FORBIDDEN`        | 在线发布版本禁止删除                                 |
| 40443 | `INDEX_BUILDING_CONFLICT`              | 该索引版本构建中，禁止重复操作                       |
| 40444 | `INDEX_COMBO_INCOMPLETE`               | 组合产物不完整，无法构建                             |
| 40446 | `INDEX_NOT_PUBLISHED`                  | 未发布任何索引版本                                   |
| 40447 | `INDEX_COLLECTION_SCHEMA_MISMATCH`     | 索引集合结构与声明不一致，请重建集合                 |
| 40448 | `INDEX_COMBO_SNAPSHOT_LEGACY`          | 索引组合快照缺失环节策略维度，请废弃重灌后重试       |
| 40449 | `INDEX_FROZEN_SCOPE_PUBLISH_FORBIDDEN` | 指定文件范围的评测冻结集禁止发布/回退                |
| 40450 | `RETRIEVAL_RULE_NOT_FOUND`             | 检索规则不存在                                       |
| 40451 | `RETRIEVAL_CAPABILITY_LOCKED`          | 该检索能力尚未启用                                   |
| 40452 | `STRATEGY_BOUND_DELETE_FORBIDDEN`      | 策略已被知识库绑定，禁止删除（可停用代替）           |
| 40453 | `STORAGE_TYPE_MISMATCH`                | 任务所属数据源与当前启用的数据源不一致，无法继续执行 |
| 40454 | `STORAGE_OBJECT_MISSING`               | 产物对象在所属存储中不存在                           |
| 40455 | `STORAGE_BACKEND_UNCONFIGURED`         | 记录所属的存储后端未配置                             |
| 40457 | `STORAGE_SOURCE_NOT_FOUND`             | 存储数据源不存在                                     |
| 40458 | `STORAGE_SOURCE_ILLEGAL`               | 存储数据源状态不允许该操作                           |
| 40459 | `STORAGE_SOURCE_PROBE_FAILED`          | 存储数据源连接失败                                   |
| 40500 | `SYSTEM_ERROR`                         | 系统异常，请稍后重试                                 |

**任务级错误码**（落在 `wb_knowledge_pipeline_task.error_code`，与上表的接口码不是同一列）：各环节自己的码见各环节文档；跨环节的公共码有三条——

| 码                      | 含义                                                                 |
|-------------------------|----------------------------------------------------------------------|
| `TASK_ENQUEUE_FAILED`   | 任务没排进队列（队列 / Redis 故障）；库里不留"排不上队"的 QUEUED     |
| `EXECUTOR_TIMEOUT`      | 看门狗判死：执行超时或进程中断留下的孤儿任务                         |
| `TASK_STORAGE_MISMATCH` | 链的数据源与当前启用的数据源不一致，产物写入被拒（五个环节都会出现） |

**环节触发与执行键**（五个环节共用，逐环节的校验顺序见各环节 `03-接口逻辑.md`）：`wb_knowledge_pipeline_task` 的**执行键**是「文件结果 + 环节 + 上游产物 + 策略版本」，落库时由唯一键 `uk_exec` 约束；`upstream_product_id` 与 `strategy_version_id` 都是 `NOT NULL DEFAULT 0`，0 分别表示"没有上游"（解析）与"该环节无策略"（解析 / 组装）。`TaskTriggerSupport.trigger` 先校验存储一致性（不一致 → `40453`），再按执行键查任务：**查到就拒 `40431`**（任何状态，含执行中，且不返回既有结果），查不到才新建 `QUEUED` 任务并入队。**QUEUED 补投完全归 `TaskStartupCompensator`**（启动补偿一次 + 低频周期扫描，默认开启、间隔 1 分钟），触发入口不做唤醒；失败即终局，再次发起要落在**新的执行键**上 —— 解析是重新提交文件，下游四个环节是换上游产物或换策略版本。

**策略来源**（只有预处理 / 切片 / 向量化三个环节有策略）：显式指定的策略版本行 → 知识库绑定的策略版本行；两档都不命中（绑定开关关闭、未绑定、绑定行缺失或已停用）一律拒 `40438`，没有「取库内启用中最新」与代码内置默认策略这两类兜底。

### 三、前端请求层

`../web/src/api/http.ts` 是全站唯一的 axios 实例；页面不直接用它，而是经 `web/src/api/*.ts` 里按领域分的薄封装调用。

- `baseURL: ''`（同源，开发期靠 Vite 代理转发）、`timeout: 15000`。
- 令牌键 `knowledge-token`，读取顺序 `localStorage ?? sessionStorage`；取到就加 `Authorization: Bearer <token>`。
- 响应拦截器统一解包与提示：

| 情况             | 拦截器行为                                                                     |
|------------------|--------------------------------------------------------------------------------|
| `code === 0`     | 把 `response.data` 替换成 `body.data`，页面拿到的是业务数据本身，不是 `R` 包装 |
| `code === 40101` | 清令牌 → 携 `redirect` 参数硬跳 `/login`（当前路径编码后作为参数值）           |
| `code === 40500` | 提示「系统异常，请稍后重试」                                                   |
| 其他非 0         | 提示 `body.msg`                                                                |
| HTTP 401 / 403   | 兜底按未认证处理，同样回登录页                                                 |
| 其他 HTTP 错误   | 提示「网络异常，请检查服务是否可用」                                           |

- **页面里 `catch {}` 留空是正常写法**：失败提示由拦截器统一负责，页面只处理成功分支。

### 四、登录态与鉴权

- 令牌是 JWT（Hutool 签发，HS256），载荷为 `sub`（用户 ID）、`username`、`role`、`tv`（令牌版本）、`exp`。**有效期分两档**，登录请求用 `remember` 选档：不勾选「记住我」按 `knowledge.auth.jwt.ttl-days`（当前 1 天），勾选按 `ttl-days-remember`（当前 30 天）。密钥来自 `knowledge.auth.jwt.secret`（当前是占位值 `knowledge-jwt-secret-change-me-in-production`，**上生产必须替换**）。
- 令牌是**无状态**的，签发后服务端无法提前作废单张令牌（只能靠 `token_version` 全量失效）；短期档到期即需重新登录，不随"还在用"自动续期。
- 前端把令牌存进 localStorage（勾「记住我」）或 sessionStorage，**并把登录响应里的用户字段存成同一档的「用户快照」**（键 `knowledge-user`：`id` / `username` / `displayName` / `email` / `phone` / `status` / `role`）。界面展示与菜单渲染只读快照，**令牌载荷仅用于判断过期**（刷新后除令牌外没有别的凭据，`exp` 只能从令牌取），**真正的鉴权始终在后端**。store 初始化时会直接丢弃过期令牌。
- 后端 `JwtAuthFilter`（`workbuddy-auth` 的 `config` 包）每个请求都过一遍：验签 → 查过期 → **比对令牌版本**（库中当前值 vs 载荷 `tv`）。改密码、改角色、停用、删除都会让该账号已签发的令牌立即失效，代价是每个带令牌请求多一次主键查询。
- **登出没有接口**：客户端丢弃令牌即可（`AppLayout.handleLogout`）。
- 路由守卫（`../web/src/router/index.ts`）：未登录访问受保护页 → 跳 `/login?redirect=<原路径>`；已登录访问 `/login` → 回 `/home`；非管理员访问标了 `meta.adminOnly` 的页 → 回 `/home`。
- `redirect` 参数是登录后跳回原页面的唯一来源（守卫与接口层的 40101 硬跳转都写它），登录页只接受以 `/` 开头且不以 `//` 开头的站内路径，其余一律回落 `/home`——不校验会变成开放重定向。

### 五、权限控制

- `SecurityConfig`（`workbuddy-auth` 的 `config` 包）：无状态（STATELESS）、关 CSRF；放行 `/auth/login`、`/auth/register`，**其余请求一律需要认证**。
- 角色控制走方法注解，由 `@EnableMethodSecurity` 开启。`@AdminOnly`（`../workbuddy-common/src/main/java/com/workbuddy/common/annotation/AdminOnly.java`）以 `@PreAuthorize` 为元注解判 `ROLE_ADMIN`，**逐个标在管理类接口的方法上**（`UserController` 的查询/新增/编辑/删除四个方法即此写法，同控制器下的个人信息接口对本人开放）。新增管理方法必须一并标注，漏标即对普通用户开放。
- **模型管理不是管理员专项**：`ModelController` 与 `ModelUsageController` 的八个方法对所有登录用户开放，方法上不标 `@AdminOnly`（口径见 `design/模型能力平台-方案设计.md` §8.1 与 `pages/模型管理/01-页面功能.md`）。
- 过滤器写入安全上下文的权限串是 `ROLE_<role>`，必须与注解表达式对得上。

### 六、限流与登录防护

两套机制独立且互补，都落在 Redis 上：

| 机制             | 数什么                   | 维度                    | 触发结果                   |
|------------------|--------------------------|-------------------------|----------------------------|
| **接口级限流**   | 请求次数（不分成败）     | 客户端 IP + 接口名      | `40201 RATE_LIMITED`       |
| **登录失败防护** | 连续失败次数（成功清零） | 账号 + IP 为主，IP 为辅 | `40111 LOGIN_TOO_FREQUENT` |

- 接口级限流：方法上标 `@RateLimit(capacity, periodSeconds, message)`。注解在 `../workbuddy-common/annotation`（契约），切面与令牌桶实现在 `../workbuddy-infra/ratelimit`（基础设施，bucket4j，桶存 Redis、跨实例共享，并带 1 小时过期，窗口过后自动回收）。用法示例见 `AuthController.login`。
- 登录失败防护：阈值与窗口由 `knowledge.auth.guard.*` 配置（默认账号+IP 维度 5 次、IP 维度 20 次、窗口 15 分钟）。实现见 `LoginAttemptGuard`。
- **计数对不存在的用户名同样生效**。
- **成功登录只清账号维度，不清 IP 维度**。
- **锁定/限流期间不查库、不比对密码**。
- **客户端 IP 只取 `getRemoteAddr()`，不读 `X-Forwarded-For`**：该请求头由客户端自行伪造，无条件信任等于把限流维度交给攻击者。将来前面加了反向代理，需按可信代理白名单解析。

### 七、前后端契约的硬口径

| 口径                  | 说明                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
|-----------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Long 按阈值序列化** | `LongIdJsonConfig`：超过 JS 安全整数上界 `2^53-1` 的整数输出字符串，其余保持数字。雪花 ID 以字符串下发，计数 / 字节数 / 耗时 / 分页号仍是数字。前端把 ID 当字符串回传即可，Jackson 反序列化到 `Long` 能正确转换。                                                                                                                                                                                                                                                                                                                                                                                   |
| **逻辑删除**          | `BaseInfo.delFlag` 带 `@TableLogic(value = DelFlag.Code.NORMAL, delval = DelFlag.Code.DELETED)`，MyBatis-Plus 自动为查询补 `del_flag = '0'`；删除是 UPDATE，不是 DELETE。码值只在 `DelFlag` 里定义一处（`NORMAL` / `DELETED`，写入取 `getCode()`、判定走 `isDeleted(...)`），业务代码统一走枚举。                                                                                                                                                                                                                                                                                                   |
| **公共字段自动填充**  | `KnowledgeMetaObjectHandler` 填 `del_flag`、`create_by`、`create_time`、`update_by`、`update_time`；`create_by` / `update_by` 取当前认证用户名，**无认证上下文时（如注册）为 null**。                                                                                                                                                                                                                                                                                                                                                                                                               |
| **主键**              | 雪花算法，`@TableId(type = IdType.ASSIGN_ID)`，由应用生成后显式插入。                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| **分页**              | 请求参数 `current`（默认 1）、`size`（默认 10）；响应是 MyBatis-Plus 的 `IPage<T>`，前端对应 `PageResult<T>`（`records` / `total` / `size` / `current` / `pages`）。                                                                                                                                                                                                                                                                                                                                                                                                                                |
| **密码规则**          | 新密码长度统一 **8-10 位**：注册、管理员新增、管理员重置、本人改密四处同一口径。登录不做长度校验，存量短密码仍可登录。                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| **码值走枚举**        | 业务代码不写码值字面量：用户状态用 `UserStatus`（1 启用 / 0 停用，前端 `USER_STATUS`）、用户角色用 `UserRole`（ADMIN / USER）、知识库状态用 `KnowledgeBaseStatus`。`of()` 解析空值或未知码值时，**写路径抛业务错误码、读路径按最保守语义处理**。                                                                                                                                                                                                                                                                                                                                                    |
| **启动校验**          | 校验项统一实现 `common/startup/StartupCheck`（`order()` 定序，`check(next)` 校验通过后调 `next.proceed()` 交棒），由 `biz/config/StartupCheckChainRunner` 按 `order()` 排序驱动，首个不通过即终止启动。新增一项校验只加一个 `@Component`，链与既有校验项都不改；不在链内的启动期校验（配置绑定、存储探活、Milvus 建连）见该驱动方注释。                                                                                                                                                                                                                                                             |
| **接口契约**          | 以后端 Controller 源码为准：路径与出入参由 `@RequestMapping` 与方法签名、`common/dto` 下的请求/响应类型给出；查询参数成组时收进共享查询对象（`PageQueryDto` / `StageTriggerQueryDto` / `StageDetailQueryDto`），DTO 字段按同名的独立查询参数绑定。                                                                                                                                                                                                                                                                                                                                                  |
| **幂等键**            | 提交类写接口用 `requestId` 做幂等：**一次提交一个**（提交端生成，通常 UUID），**同一次提交的重试沿用同一个值、换一次提交换新值**；服务端靠 `wb_submit_log.request_id` 唯一约束 + 查重回放实现（同键重复到达返回首次记录，不重复落库；并发撞唯一键也转回放而不是报错）。它**不是按业务对象去重的手段**：同一份文件重新上传会拿到新的 fileId（`DefaultFileStorage.putFile` 每次 `IdWorker.getIdStr()`），对系统而言就是两份文件、应当各建一条档案 —— 把键写成"文件名 + 大小 + 修改时间"之类的文件指纹，会让再导入静默变成幂等回放（界面显示成功、实际未建档）。为空报 `REQUEST_ID_MISSING`（40420）。 |

### 八、开发期联调

- 前端 dev server：`127.0.0.1:5173`（Vite）；后端：`localhost:4388`。
- 跨域靠 Vite 代理（`../web/vite.config.ts`），已配置前缀：`/auth`、`/files`、`/file-results`、`/knowledge-base`、`/strategy-versions`、`/system`、`^/user/`、`^/home/`、`^/model/`。
- **`/home`、`/user`、`/model` 既是 SPA 路由又是接口前缀**，靠 `bypass` 按 `Accept` 头区分：含 `text/html` 的导航请求交回 SPA 回退，接口请求才转发后端。`/knowledge` 只作 SPA 路由前缀（接口前缀是 `/knowledge-base`），无需代理。
- 新增接口前缀必须同时加代理。漏加时代理不生效：请求落到 SPA 回退，前端拿到 HTML、页面数据全空，**控制台不报错**。

### 九、空值判定口径

- 后端判空统一走 `NullUtil.isNull/isNotNull`（`../workbuddy-common/src/main/java/com/workbuddy/common/utils/NullUtil.java`）：参数标注可空、内部转发 Hutool `ObjectUtil`，语义与 Hutool 完全一致。
- **不写裸 `== null` / `!= null`，也不直接调 `ObjectUtil.isNull/isNotNull`**：Hutool 判空方法的参数没有可空注解，SpotBugs 把"被静态证明为 null"的判空误报为空指针类告警（`NP_NONNULL_PARAM_VIOLATION` / `NP_LOAD_OF_KNOWN_NULL_VALUE`）；走 `NullUtil` 后这类告警不再出现。
- **`tools/backend/spotbugs-exclude.xml` 不接受新增 `NP_*` 豁免**：撞上这类告警就改判空写法，别往豁免清单加条目。
- 其它判空语义（`ObjectUtil.isEmpty` / `isNotEmpty` / `defaultIfNull` 等）不属于本口径，按原样使用。

### 十、前端信息架构

侧栏是**两级菜单**（`../web/src/layouts/AppLayout.vue` 的 `navItems`）：没有二级项的是一级叶子项，带二级项的是一级分组（自身不下钻，点标题开合）。当前结构：

| 层级 | 菜单       | 路由                  | 可见性                     |
|------|------------|-----------------------|----------------------------|
| 一级 | 首页       | `/home`               | 全部                       |
| 一级 | 知识库     | 分组，无自身路由      | 全部                       |
| 二级 | 知识库管理 | `/knowledge/manager`  | 全部                       |
| 二级 | 策略管理   | `/knowledge/strategy` | 全部                       |
| 一级 | 用户管理   | `/user`               | 仅管理员                   |
| 一级 | 模型管理   | 分组，无自身路由      | 全部                       |
| 二级 | 模型清单   | `/model/manager`      | 全部                       |
| 二级 | 用量与成本 | `/model/usage`        | 全部                       |
| 一级 | 系统设置   | `/settings`           | 全部（页内卡片按权限显示） |
| 一级 | 个人中心   | `/profile`            | 全部                       |

- 下钻页（`/knowledge/:id/stages`、`.../index`、`.../retrieval`）**不单独占菜单项**，归到二级「知识库管理」下点亮。
- **新增一级模块**（提示词版本管理、Agent 对话等）在 `navItems` 里加一项：内容多就照「知识库」的样子加一个分组，只有一个页面就用叶子项。
- **菜单可见性有两处声明**（菜单项的 `adminOnly` 与路由的 `meta.adminOnly`），新增管理类页面要同步改两处（见 `pages/布局壳/README.md` 的待定项）。
- 菜单开合、选中态级联与折叠态浮出面板的交互口径见 `pages/布局壳/01-页面功能.md`。

### 十一、待补

梳理后续页面时一旦遇到就回填本文档：

- 各页面的数据刷新节奏（轮询 / 手动刷新）暂记在各页面文件里，若形成统一约定再上移到这里。

## 已定口径

平台级口径现状。

| 事项                                        | 结论                               |
|---------------------------------------------|------------------------------------|
| 登录会话管理（在线设备列表 + 单踢）         | **不做**                           |
| 对接 SSO（AD/LDAP/OAuth）与 MFA             | **不做**                           |
| 密码复杂度策略（引 Passay）                 | **不做**                           |
| 自助注册开关                                | **保持开放**                       |
| 登录失败锁定的维度                          | 账号 + IP 为主、IP 为辅            |
| 解析环节的模型能力（OCR / 版面 / 表格结构） | **暂不接入**（规则路径为当前实现） |

## 关联文档

| 文档                                           | 内容                                                                                                     |
|------------------------------------------------|----------------------------------------------------------------------------------------------------------|
| `../README.md`（仓库根）                       | 项目定位、模块划分与依赖、构建命令                                                                       |
| `../.agent/standards/frontend/前端开发规范.md` | 前端编码与注释规范                                                                                       |
| `../.agent/checkers/backend.md`                | 后端检查组成与注释规范                                                                                   |
| `../.agent/checkers/frontend.md`               | 前端检查的组成与配置归属                                                                                 |
| `../docker/README.md`                          | 中间件编排与数据库迁移脚本                                                                               |
| `stages/解析环节/README.md`                    | 解析环节的环节定位、代码索引与待定项；主体见同目录 `01-功能逻辑.md`                                      |
| `research/解析环节-开源方案调研.md`            | 解析环节的市面做法对照（可直接照搬 / 需改造后采用 / 明确不适用）与出处                                   |
| `stages/组装环节/README.md`                    | 组装环节的环节定位、代码索引与待定项；主体见同目录 `01-功能逻辑.md`                                      |
| `stages/预处理环节/README.md`                  | 预处理环节的环节定位、代码索引与待定项；主体见同目录 `01-功能逻辑.md`                                    |
| `research/预处理环节-开源方案调研.md`          | 预处理环节的市面做法对照与本轮审计映射（含核实后不成立的项）与出处                                       |
| `stages/切片环节/README.md`                    | 切片环节的环节定位、代码索引与待定项；主体见同目录 `01-功能逻辑.md`                                      |
| `research/切片环节-开源方案调研.md`            | 切片环节的市面做法对照（Unstructured / Docling 已展开核对）与审计映射                                    |
| `design/对象存储多后端-方案对比.md`            | 对象存储多后端的现状、根因、目标态、方案对比与落地路径                                                   |
| `design/模型能力平台-方案设计.md`              | 模型能力平台（多厂商接入、用量与成本计量、按流量切模型、模型管理页面）的目标、架构、数据模型与落地批次   |
| `design/实现-01-适配器骨架与真实调用.md`       | 模型能力平台第一片实现文档——适配器骨架与真实调用                                                         |
| `design/模型能力平台-待办清单.md`              | 模型能力平台待办清单（`MODEL-xx`）                                                                       |
| `research/模型能力平台-市面产品调研.md`        | 四项目标的市面做法对照（网关路由 / 计量成本 / 管理后台）、可直接照搬与不适用的清单、对方案的修正项与出处 |
