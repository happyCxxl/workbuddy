# 参考实现清单

> 动新功能前在本表找同场景的一份，照它的形态写（import 顺序、方法顺序、注释密度、异常用法）。
> 本表只放指针，不复制代码；被指向的文件是「全库最规范的那一份」。

## 1. 按场景选参考

| 场景                 | 参考文件                                                                                                                                                                                                                       | 标准点                                                                                                            |
|----------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------|
| 新增一组 CRUD 端点   | `biz/controller/model/ModelController` 与 `biz/service/ModelService`、`biz/service/impl/ModelServiceImpl`、`common/dto/request/model/ModelQueryDto`、`ModelSaveDto`、`ModelUpdateDto`、`common/dto/response/model/ModelItemVO` | 四个方法覆盖分页、新增、更新、状态；请求体全部 `@Valid`；返回 `R<XxxVO>` 或 `R<IPage<XxxVO>>`；类注释写清可见范围 |
| 事务边界与多写一致性 | `biz/service/impl/KnowledgeBaseServiceImpl` 的建档方法                                                                                                                                                                         | 三次写库同一事务；外部 IO 在事务外；唯一键冲突转幂等回放                                                          |
| 单表数据访问门面     | `biz/service/db/WbSubmitLogDbService` 与 `biz/service/db/impl/WbSubmitLogDbServiceImpl`                                                                                                                                        | 一表一接口；方法注释写查询口径（排序、过滤、空值语义）；不含业务判断                                              |
| 实体到 VO 的纯映射   | `biz/service/support/InputVoAssembler`                                                                                                                                                                                         | 只搬字段；来源对象缺失时留空；不查库、不判断                                                                      |
| 守卫与错误码         | `common/exception/ThrowUtil`、`common/error/ErrorCode`                                                                                                                                                                         | 业务拒绝与内部缺陷两条口径分开；码值一处登记                                                                      |
| 全局异常出口         | `biz/config/GlobalExceptionHandler`                                                                                                                                                                                            | 唯一出口；业务异常 warn、系统异常 error；HTTP 恒 200                                                              |
| 环节任务执行器       | `biz/task/runner/ParseTaskRunner`、`biz/task/support/TaskRunnerSupport`                                                                                                                                                        | 领任务、校验上游、执行、终态收口四段；失败归一成任务错误码落库                                                    |
| 公共字段与逻辑删除   | `biz/config/KnowledgeMetaObjectHandler`、`common/domain/entity/BaseInfo`                                                                                                                                                       | 自动填充与逻辑删除走基类，业务代码不手写                                                                          |
| 前端接口层           | `web/src/api/knowledge-base.ts`                                                                                                                                                                                                | 一控制器一文件；返回类型显式声明；不写 `any`                                                                      |

## 2. 不要抄（已知偏离）

| 偏离                         | 文件                                                                                                                                                                                                | 正确写法                                       |
|------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------|
| 请求对象 `*Request` 后缀     | `LoginRequest`、`RegisterRequest`、`UserCreateRequest`、`UserUpdateRequest`、`ProfileUpdateRequest`、`PasswordUpdateRequest`、`FileSubmitRequest`、`SearchRequest`、`StrategyBindingsUpdateRequest` | 统一 `Xxx<动作>Dto`；存量冻结，新代码不新增    |
| 响应对象 `*Response` 后缀    | `FileSubmitResponse`                                                                                                                                                                                | 统一 `XxxVO`                                   |
| 响应对象 `*View` 后缀        | `TaskStatusView`                                                                                                                                                                                    | 统一 `XxxVO`                                   |
| 请求对象 `*Filter` 后缀      | `ChunkContentFilter`                                                                                                                                                                                | 统一 `XxxQueryDto`                             |
| `@author` 标注               | 全库 845 处                                                                                                                                                                                         | 新代码不写；存量随模块收敛删除                 |
| 800 行以上的 ServiceImpl     | `IndexSetServiceImpl`、`KnowledgeBaseServiceImpl`                                                                                                                                                   | 按职责拆到 Service + DbService + Assembler     |
| 1000 行以上的测试类          | `IndexSetServiceImplTest`                                                                                                                                                                           | 400 行上限，超出抽 `XxxTestSupport`            |
| Controller 内直接查库或拼 VO | 无（避免出现）                                                                                                                                                                                      | 见 `.agent/standards/java/01-分层与依赖.md` §3 |

## 3. 使用顺序

1. 按 §1 定位同场景参考，通读整个类，不只看目标方法。
2. 对照 §2 确认没有抄到偏离写法。
3. 写完对照 `java/00-总则与规则索引.md` §6 评审项逐条自查。
4. 出现比参考更规范的写法时，先改本表指针，再让代码跟上；本表始终指向最规范的那一份。
