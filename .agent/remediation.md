# 存量整改清单

> 记录**已确认但尚未整改**的规范偏离。每修完一条删一条，清零后删除本文件。
> 新增偏离必须先判定是「本次就改」还是「登记待改」，不得留在代码里不登记。

## 1. 分层偏离

| 编号 | 位置                                   | 偏离                                                                                       | 依据           | 计划                                                                                              |
|------|----------------------------------------|--------------------------------------------------------------------------------------------|----------------|---------------------------------------------------------------------------------------------------|
| R-01 | `biz/controller/auth/AvatarController` | 直接注入 `WbUserDbService` 查库、直接注入 `FileStorage` 读写对象存储、import `WbUser` 实体 | JAVA-LAYER-002 | 查库下沉到 `UserService`，文件读写下沉到 `FileStorage` 的 biz 封装，Controller 只留参数绑定与响应 |
| R-02 | `biz/controller/file/FileController`   | 直接 import `filecenter.service.FileService` 与 `FileStorage`，绕过 biz 的 Service 层      | JAVA-LAYER-002 | 在 `biz/service` 增加文件服务门面，Controller 只依赖它                                            |

## 2. 单文件超过 400 行（26 个）

判定：主代码 `JAVA-LAYER-005`、测试 `JAVA-TEST-006`。拆分按数据访问、业务判断、映射三类拆，不靠行内折叠。规模扫描接入后以本清单为豁免依据。

### 主代码（7）

| 行数 | 文件                           |
|------|--------------------------------|
| 892  | `IndexSetServiceImpl`          |
| 875  | `KnowledgeBaseServiceImpl`     |
| 544  | `StageContentQueryServiceImpl` |
| 507  | `PdfPageExtractor`             |
| 471  | `ChunkPipeline`                |
| 445  | `LineageQueryServiceImpl`      |
| 417  | `EmbedPipeline`                |

### 测试（19）

| 行数 | 文件                               |
|------|------------------------------------|
| 1128 | `IndexSetServiceImplTest`          |
| 996  | `PdfBoxDocumentParserTest`         |
| 826  | `KnowledgeBaseServiceImplTest`     |
| 642  | `ParsePipelineTest`                |
| 635  | `UserServiceImplGuardTest`         |
| 616  | `ChunkControlServiceImplTest`      |
| 579  | `PreprocessControlServiceImplTest` |
| 570  | `StageContentQueryServiceImplTest` |
| 538  | `KnowledgeBaseFileSubmitTest`      |
| 517  | `StrategyVersionServiceImplTest`   |
| 515  | `EmbedControlServiceImplTest`      |
| 508  | `ChunkPipelineTest`                |
| 499  | `ChunkTaskRunnerTest`              |
| 482  | `StorageSourceServiceImplTest`     |
| 480  | `LineageQueryServiceImplTest`      |
| 470  | `ExcelDocumentParserTest`          |
| 459  | `EmbedTaskRunnerTest`              |
| 443  | `StructureControlServiceImplTest`  |
| 424  | `DocxDocumentParserTest`           |
