# 存量整改清单

> 记录**已确认但尚未整改**的规范偏离。每修完一条删一条，清零后删除本文件。
> 新增偏离必须先判定是「本次就改」还是「登记待改」，不得留在代码里不登记。

| 编号 | 位置                                   | 偏离                                                                                       | 依据           | 计划                                                                                              |
|------|----------------------------------------|--------------------------------------------------------------------------------------------|----------------|---------------------------------------------------------------------------------------------------|
| R-01 | `biz/controller/auth/AvatarController` | 直接注入 `WbUserDbService` 查库、直接注入 `FileStorage` 读写对象存储、import `WbUser` 实体 | JAVA-LAYER-002 | 查库下沉到 `UserService`，文件读写下沉到 `FileStorage` 的 biz 封装，Controller 只留参数绑定与响应 |
| R-02 | `biz/controller/file/FileController`   | 直接 import `filecenter.service.FileService` 与 `FileStorage`，绕过 biz 的 Service 层      | JAVA-LAYER-002 | 在 `biz/service` 增加文件服务门面，Controller 只依赖它                                            |
