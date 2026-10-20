# 数据库初始化

`init.sql` 是本目录唯一的建库脚本：从零一次跑完即可得到应用所需的全部结构与初始数据。

## 一、脚本做什么

1. `DROP DATABASE IF EXISTS workbuddy` + `CREATE DATABASE workbuddy` + `USE workbuddy`——库名固定为 `workbuddy`，与
   `../../../../src/main/resources/application.yml` 中 `spring.datasource.url` 一致；
2. 按依赖顺序建 21 张表（被引用的表在前，无外键约束）；
3. 写入应用运行必需的初始数据（4 条环节策略版本 + 1 个管理员账号）。

**脚本会删除同名库，库内原有数据全部丢弃；可重复执行，每次执行结果一致。**

## 二、怎么执行

命令行（密码取自 `application.yml` 的 `spring.datasource.password`）：

```powershell
# 在仓库根目录执行
mysql -h localhost -P 3306 -uroot -proot123 < workbuddy-biz/src/main/resources/sql/init.sql
```

```bash
# Linux / macOS 同款写法
mysql -h localhost -P 3306 -uroot -proot123 < workbuddy-biz/src/main/resources/sql/init.sql
```

Docker 环境（`docker/docker-compose.yml` 把本目录挂载到 MySQL 的 `/docker-entrypoint-initdb.d`）：

```powershell
cd docker
docker compose down -v      # 清空数据卷，否则初始化脚本不会重跑
docker compose up -d
```

也可直接在客户端（Navicat / DataGrip / IDEA 数据库工具）里整体执行本文件。

## 三、21 张表

| #  | 表名                                     | 一句话职责                                                                      |
|----|------------------------------------------|---------------------------------------------------------------------------------|
| 1  | `wb_knowledge_base`                      | 知识库：一个业务场景一行，承载策略绑定、默认检索规则与一级发布指针              |
| 2  | `wb_audit_log`                           | 操作审计：管理员关键操作留痕，append-only                                       |
| 3  | `wb_file_object`                         | 文件档案：上传文件元数据（桶名、对象键、内容指纹），append-only                 |
| 4  | `wb_source_file`                         | 来源文件：文件引用与 sha256 指纹，append-only                                   |
| 5  | `wb_file_result`                         | 文件结果：一次提交一行，同文件重复提交产生多行                                  |
| 6  | `wb_submit_log`                          | 提交日志：每次提交一条（无论成败），`request_id` 为幂等键                       |
| 7  | `wb_knowledge_pipeline_task`             | 处理链任务：一次环节触发一条任务及状态机                                        |
| 8  | `wb_knowledge_pipeline_product`          | 阶段产物：产物本体在对象存储，表内存引用与血缘                                  |
| 9  | `wb_knowledge_pipeline_step_log`         | 子步骤记录：环节内每个子步骤一条及统计                                          |
| 10 | `wb_knowledge_pipeline_strategy_version` | 环节策略版本：PREPROCESS/CHUNK/EMBED/RETRIEVAL 四类策略的不可变快照             |
| 11 | `wb_knowledge_strategy_binding`          | 知识库-策略绑定：知识库按策略类型绑定一个策略版本                               |
| 12 | `wb_knowledge_chunk_set`                 | 切片产物集合：一次切片策略运行一行                                              |
| 13 | `wb_knowledge_chunk`                     | 切片：检索命中的最小单元                                                        |
| 14 | `wb_knowledge_embedding_set`             | 向量产物集合：一次 EMBED 运行一行，存引用与血缘                                 |
| 15 | `wb_knowledge_embedding_record`          | 向量记录：一个切片一条，向量本体只进集合文件                                    |
| 16 | `wb_knowledge_index_set`                 | 索引集合：一知识库一行，承载二级发布指针                                        |
| 17 | `wb_knowledge_index_version`             | 索引版本行：组合快照 + 状态机 + 发布/退役留痕                                   |
| 18 | `wb_knowledge_retrieval_run`             | 检索运行记录：四元组 + 执行时刻结果快照（评测原始数据）                         |
| 19 | `wb_user`                                | 用户：登录账号、角色与令牌版本                                                  |
| 20 | `wb_system_setting`                      | 系统设置：键值形态的单行配置，表留在库里备用                                    |
| 21 | `wb_storage_source`                      | 存储数据源：一行一个实例（含连接参数），`is_current` 那一行决定新对象的写入位置 |

## 四、初始数据

### 管理员账号

| 用户名  | 密码        | 角色    | 状态 | 姓名   |
|---------|-------------|---------|------|--------|
| `admin` | `Admin@123` | `ADMIN` | 启用 | 管理员 |

> **首次登录后请立即修改密码。** 该密码写在脚本里，属于公开信息，仅用于完成首次登录。
> 修改密码、改角色、停用账号都会递增 `token_version`，使已签发的旧令牌立即失效。

### 环节策略版本（`wb_knowledge_pipeline_strategy_version`，4 条 ACTIVE）

| type         | name                          | version | 说明                                                       |
|--------------|-------------------------------|---------|------------------------------------------------------------|
| `PREPROCESS` | `preproc-default`             | `v1`    | 七规则默认开，页眉页脚/目录/噪声只标记；与代码侧模板同参   |
| `CHUNK`      | `chunk-hybrid`                | `v1`    | 段落聚合 + 行级表切片 + 递归兜底；与代码侧模板同参         |
| `EMBED`      | `embed-default`               | `v1`    | 默认模型 `text-embedding-v4`，账本复用开；与代码侧模板同参 |
| `RETRIEVAL`  | `hybrid-rrf-k60-top10-parent` | `v1`    | 混合 RRF（k=60，top10）+ 父片展开；与引擎基线同参          |

这 4 条是策略管理页面的初始可选项，也是新建知识库时可直接绑定的默认策略；
环节执行只认任务行上的策略快照（快照取自这些行），脚本漏执行时任务会明确失败，不会改用代码里写死的默认参数。

其余表不插入任何数据：知识库、绑定、索引与产物均由使用过程中产生。`wb_storage_source` 初始由本脚本插入两行（`MinIO` 与 `本地磁盘`），当前启用读库里的 `is_current`；表为空或没有可用当前行时应用启动失败，不在运行期按配置补行。`wb_system_setting` 初始为空且当前没有读写它的功能。

## 五、重建库之后必须清空对象存储

数据库与对象存储是两套独立存储，产物本体（解析产物、切片集合、向量集合）与上传文件都不在 MySQL 里：

- 文件服务数据源由本脚本初始化；本地形态的数据在 `local.rootDir`（默认 `./data/file-center`），MinIO 形态的数据在 `files` / `artifacts` 桶（`docker/volumes/knowledge-minio`）。记录里带了数据源 ID 与存储类型，读取按记录里的数据源走，与当前启用的是哪一行无关。
- 库重建后自增主键与雪花 ID 全部重新开始，旧对象恰好可能被新记录引用到，**新库会指向旧产物**，出现内容错乱且难以排查。

因此重建库（无论用 `init.sql` 还是重建容器）时必须同时清空对象存储：

```powershell
# 本地存储形态
Remove-Item -Recurse -Force .\data\file-center\*

# MinIO 形态：清空 files 与 artifacts 桶（MinIO 控制台 http://localhost:9001，或清空挂载目录）
Remove-Item -Recurse -Force .\docker\volumes\knowledge-minio\*
```

> MySQL 数据目录与 MinIO 数据目录都是 `docker/volumes/` 下的 bind mount，`docker compose down -v` 不会删除它们。
> 重建库的做法：`docker compose down` → 清空 `docker/volumes/mysql`（连带上面的对象存储目录）→ `docker compose up -d`，
> 首次初始化会重跑 `init.sql`。
