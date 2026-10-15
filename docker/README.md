# 本地开发基础设施（Docker）

本目录提供本地开发所需的基础设施编排。

## 前置

- Docker Desktop 已安装并启动引擎（引擎图标变绿）。
- 首次安装 Docker 前需先启用 WSL2：管理员 PowerShell 执行 `wsl --install` 并重启。
- 宿主机 3306 端口空闲。Windows 本机 MySQL 服务名 `MySQL80`，停用命令（管理员 PowerShell）：
  ```powershell
  Stop-Service MySQL80 -Force; Set-Service MySQL80 -StartupType Disabled
  ```
  数据仍在本机 `C:\ProgramData\MySQL\MySQL Server 8.0\Data`。
- 本机 `~/.docker/daemon.json` 配好 `registry-mirrors`，改完需完全重启 Docker Desktop。
  验证：`docker info --format '{{json .RegistryConfig.Mirrors}}'`。

## 镜像来源

| 镜像                   | 来源                                                                                         |
|------------------------|----------------------------------------------------------------------------------------------|
| MySQL / Redis / Milvus | Docker Hub（`mysql:8.0`、`redis:7-alpine`、`milvusdb/milvus:v2.5.5`），走 `registry-mirrors` |
| etcd                   | `quay.io/coreos/etcd:v3.5.16`                                                                |
| MinIO                  | `cgr.dev/chainguard/minio`，digest 钉版（RELEASE.2026-09-22T19-25-18Z，多架构清单）          |

MinIO 镜像为 `cgr.dev/chainguard/minio`，与官方镜像的差异（compose 中已处理）：

- entrypoint 已是 `minio`，`command` 中不写 `minio`。
- 镜像内无 `curl`/`wget`，健康检查用自带的 `mc ls local`，别名由 `MC_HOST_local` 提供。
- 默认以 UID 65532 非 root 运行，compose 中显式 `user: "0:0"`。

## 换机需重建的机器级配置

`docker-compose.yml` 与机器无关，下列配置在仓库外，换机需重建：

1. `~/.docker/daemon.json` 的 `registry-mirrors`（示例：`https://docker.m.daocloud.io`、
   `https://docker.1ms.run`），改完完全重启 Docker Desktop。
2. 宿主机 3306 / 6379 / 19530 / 9091 / 9000 / 9001 端口空闲。
3. Docker Desktop 已启动、WSL2 可用（Windows）。

Linux 宿主：`./volumes` 下目录由容器以 root 创建，宿主用户无权限时用 `sudo rm -rf volumes` 删除。
## 启动

```powershell
cd D:\projects-personal\knowledge\docker
docker compose up -d
```

首次启动自动拉取镜像，并执行 `../workbuddy-biz/src/main/resources/sql/init.sql`（单文件初始化脚本：建库 +
23 张表 + 必需初始数据，脚本自带 `DROP DATABASE` / `CREATE DATABASE` / `USE workbuddy`），
只在**数据卷为空**时执行一次；改了脚本想重来需 `docker compose down -v` 后再 `up -d`。

## 组件与端口

| 组件         | 端口                            | 账号                    | 说明                                                                                     |
|--------------|---------------------------------|-------------------------|------------------------------------------------------------------------------------------|
| MySQL 8      | 3306                            | root / root123          | 首次启动自动建库 `workbuddy` + 全部表 + 策略 seed                                        |
| Redis 7      | 6379                            | 无密码                  | 任务唤醒队列 / 启动补偿分布式锁                                                          |
| Milvus 2.5.5 | 19530（gRPC）/ 9091（健康检查） | —                       | 向量库；配套 etcd + minio 内部容器；应用启动时缺库自动建 `knowledge`（只自带 `default`） |
| MinIO        | 9000（S3）/ 9001（控制台）      | minioadmin / minioadmin | 文件服务模块专用对象存储（`workbuddy-minio`）；Milvus 自用的 minio 不对外映射端口        |

与 `application.yml` 的对应关系：

- `spring.datasource.*` → mysql 容器
- `spring.data.redis.*` → redis 容器
- `knowledge.vector.milvus.host` / `port` → milvus 容器（默认 localhost:19530，未在 yml 中显式配置）
- `knowledge.vector.milvus.database` → 目标库名（默认 `knowledge`）：Milvus 只自带 `default` 库，连接时指定的库必须已存在，故启动先探测、缺库即以 `default` 库连一次并创建（与集合懒建同口径）；置 `knowledge.vector.milvus.enable=false` 可整体跳过向量库

## 常用命令

```powershell
docker compose ps          # 查看容器状态
docker compose logs -f milvus   # 看某个容器日志
docker compose down        # 停止（数据保留）
docker compose down -v     # 停止并清空数据卷（重置环境）

# 健康检查：容器状态 + 端口占用 + 各组件可用性（redis PING、milvus /healthz、
# minio S3 读写成环、mysql 表数）
pwsh -File .\healthcheck.ps1
```
