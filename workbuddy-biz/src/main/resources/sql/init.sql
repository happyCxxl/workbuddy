-- ============================================================================
-- 知识库平台初始化脚本（从零建库，可重复执行）
--
-- 执行方式：
--   mysql -h localhost -P 3306 -uroot -p < init.sql
--   或在客户端中整体执行本文件
--
-- 本脚本会先删除同名库再重建，库内原有数据全部丢弃。
-- 库重建后对象存储中的旧产物与新库不再对应，需一并清空（见同目录 README.md）。
-- ============================================================================

DROP DATABASE IF EXISTS workbuddy;
CREATE DATABASE workbuddy DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE workbuddy;

-- ============================================================================
-- 一、知识库
-- ============================================================================

CREATE TABLE wb_knowledge_base
(
    id                         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键（雪花，应用显式传 id）',
    name                       VARCHAR(128) NOT NULL COMMENT '知识库名称',
    description                VARCHAR(512)          DEFAULT NULL COMMENT '业务场景说明',
    status                     TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 启用 / 0 停用',
    strategy_binding_enabled   TINYINT      NOT NULL DEFAULT 1 COMMENT '策略绑定开关：1 开启（触发默认走 KB 绑定策略）/ 0 关闭（触发必须显式选策略）',
    binding_profile_version_id BIGINT                DEFAULT NULL COMMENT '绑定处理策略版本（三件套之一）',
    index_config               varchar(1024)                  DEFAULT NULL COMMENT '索引配置（三件套之一）：自动建索引开关/形态/更新策略',
    default_rule_id            BIGINT                DEFAULT NULL COMMENT '默认检索规则（三件套之一；wb_knowledge_pipeline_strategy_version.id，type=RETRIEVAL）',
    published_index_set_id     BIGINT                DEFAULT NULL COMMENT '当前发布索引（一级发布指针；wb_knowledge_index_set.id）',
    user_id                    BIGINT                DEFAULT NULL COMMENT '创建用户ID',
    del_flag                   VARCHAR(1)   NOT NULL DEFAULT '0' COMMENT '删除标记：0 正常 / 1 已删',
    create_by                  VARCHAR(64)           DEFAULT NULL COMMENT '创建人',
    create_time                DATETIME              DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by                  VARCHAR(64)           DEFAULT NULL COMMENT '更新人',
    update_time                DATETIME              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_del_flag (del_flag)
) ENGINE = InnoDB
   COMMENT ='知识库：一个业务场景 = 一个知识库';

CREATE TABLE wb_audit_log
(
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键（雪花，应用显式传 id）',
    action_type    VARCHAR(32)  NOT NULL COMMENT '操作类型：见 AuditActionType 枚举（知识库 / 索引 / 检索规则 / 用户 / 系统设置）',
    object_type    VARCHAR(32)  NOT NULL COMMENT '对象类型',
    object_id      VARCHAR(64)  NOT NULL COMMENT '对象 ID',
    before_summary VARCHAR(1024)         DEFAULT NULL COMMENT '变更前摘要',
    after_summary  VARCHAR(1024)         DEFAULT NULL COMMENT '变更后摘要',
    user_id        BIGINT                DEFAULT NULL COMMENT '操作用户ID',
    del_flag       VARCHAR(1)   NOT NULL DEFAULT '0' COMMENT '删除标记：0 正常 / 1 已删',
    create_by      VARCHAR(64)           DEFAULT NULL COMMENT '创建人',
    create_time    DATETIME              DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by      VARCHAR(64)           DEFAULT NULL COMMENT '更新人',
    update_time    DATETIME              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_object (object_type, object_id)
) ENGINE = InnoDB
   COMMENT ='操作审计：管理员关键操作留痕（append-only）';

-- ============================================================================
-- 二、文件档案与文档输入
-- ============================================================================

CREATE TABLE wb_file_object
(
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键（雪花，应用显式传 id）',
    file_id     VARCHAR(64)  NOT NULL                COMMENT '文件 ID（雪花字符串，对象存储键）',
    file_name   VARCHAR(255) NOT NULL                COMMENT '文件名',
    mime_type   VARCHAR(128)          DEFAULT NULL   COMMENT 'MIME 类型',
    file_size   BIGINT                DEFAULT NULL   COMMENT '文件大小（字节）',
    sha256       CHAR(64)     NOT NULL                COMMENT '内容指纹（sha256）',
    storage_type      VARCHAR(32)  NOT NULL                COMMENT '存储类型（StorageType 枚举码：minio / local；冗余留作展示与降级）',
    storage_source_id BIGINT       NOT NULL                COMMENT '数据源实例 ID（wb_storage_source.id）：读取按它定位后端',
    bucket            VARCHAR(64)  NOT NULL                COMMENT '存储桶名（local 下为一级子目录）',
    object_key  VARCHAR(128) NOT NULL                COMMENT '对象键',
    create_by   VARCHAR(64)           DEFAULT NULL   COMMENT '上传人',
    create_time DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '上传时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_file_id (file_id)
) COMMENT ='文件档案：上传文件元数据（append-only）';

CREATE TABLE wb_source_file
(
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键（雪花，应用显式传 id）',
    file_id        VARCHAR(64)  NOT NULL                COMMENT '文件 ID',
    sha256         CHAR(64)     NOT NULL                COMMENT '文件内容指纹（输入身份）',
    file_name      VARCHAR(255) NOT NULL                COMMENT '文件名',
    mime_type      VARCHAR(128)          DEFAULT NULL   COMMENT 'MIME 类型',
    file_size      BIGINT                DEFAULT NULL   COMMENT '文件大小（字节）',
    input_snapshot varchar(1024)                  DEFAULT NULL   COMMENT '文件档案快照（JSON 文本）',
    user_id        BIGINT                DEFAULT NULL   COMMENT '上传用户ID',
    create_by      VARCHAR(64)           DEFAULT NULL   COMMENT '创建人（=提交人）',
    create_time    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY idx_file_id (file_id),
    KEY idx_sha256 (sha256)
)  COMMENT ='来源文件：文件引用与指纹（append-only）';

CREATE TABLE wb_file_result
(
    id                BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键（雪花，应用显式传 id）',
    knowledge_base_id BIGINT      NOT NULL                COMMENT '所属知识库',
    owner             VARCHAR(64) NOT NULL DEFAULT 'ADMIN' COMMENT '用户归属（检索强制过滤口径）',
    source_file_id    BIGINT      NOT NULL                COMMENT '原始文件引用',
    user_id           BIGINT               DEFAULT NULL   COMMENT '创建用户ID',
    del_flag          VARCHAR(1)  NOT NULL DEFAULT '0'    COMMENT '删除标记：0 正常 / 1 已删',
    deleted_at        DATETIME(3)          DEFAULT NULL   COMMENT '逻辑删除时间',
    create_by         VARCHAR(64)          DEFAULT NULL   COMMENT '创建人',
    create_time       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    update_by         VARCHAR(64)          DEFAULT NULL   COMMENT '更新人',
    update_time       DATETIME(3)          DEFAULT NULL   COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_kb (knowledge_base_id),
    KEY idx_source (source_file_id),
    KEY idx_del_flag (del_flag),
    KEY idx_owner (owner)
)  COMMENT ='文件结果：一次提交一行（同文件重复提交 = 多行，复用 wb_source_file）';

CREATE TABLE wb_submit_log
(
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键（雪花，应用显式传 id）',
    knowledge_base_id BIGINT       NOT NULL                COMMENT '目标知识库',
    request_id        VARCHAR(64)  NOT NULL                COMMENT '幂等键',
    file_id           VARCHAR(64)  NOT NULL                COMMENT '文件 ID',
    sha256            CHAR(64)     NOT NULL                COMMENT '文件指纹（校验失败存空串占位）',
    file_name         VARCHAR(255) NOT NULL                COMMENT '文件名',
    file_result_id    BIGINT                DEFAULT NULL   COMMENT '建档回填的文件结果 ID（校验失败为空）',
    status            VARCHAR(16)  NOT NULL                COMMENT '提交结果：PASS / FAIL',
    fail_reason       VARCHAR(512)          DEFAULT NULL   COMMENT '失败原因',
    user_id           BIGINT                DEFAULT NULL   COMMENT '提交用户ID',
    del_flag          VARCHAR(1)   NOT NULL DEFAULT '0'    COMMENT '删除标记：append-only 恒 0',
    create_by         VARCHAR(64)           DEFAULT NULL   COMMENT '创建人（=提交人）',
    create_time       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '提交时间',
    update_by         VARCHAR(64)           DEFAULT NULL   COMMENT '更新人',
    update_time       DATETIME(3)           DEFAULT NULL   COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY idx_request_id (request_id),
    KEY idx_kb_file (knowledge_base_id, file_id)
)  COMMENT ='提交日志：每次提交一条，无论成败';

-- ============================================================================
-- 三、处理链
-- ============================================================================

CREATE TABLE wb_knowledge_pipeline_task
(
    id                  BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键（雪花，应用显式传 id）',
    file_result_id      BIGINT               DEFAULT NULL   COMMENT '所属文件结果（BUILD_INDEX 知识库级任务为空）',
    stage               VARCHAR(32) NOT NULL                COMMENT '环节：PARSE/STRUCTURE/PREPROCESS/CHUNK/EMBED/BUILD_INDEX',
    upstream_product_id BIGINT      NOT NULL DEFAULT 0      COMMENT '上游产物 ID（0 = 没有上游：解析是链路起点）',
    strategy_version_id BIGINT      NOT NULL DEFAULT 0      COMMENT '策略版本 ID（0 = 该环节无策略：解析/组装；其余取 wb_knowledge_pipeline_strategy_version.id）',
    strategy_snapshot   varchar(1024)                 DEFAULT NULL   COMMENT '环节策略快照（触发时固定）',
    product_id          BIGINT               DEFAULT NULL   COMMENT '成功运行回写的产物 ID（wb_knowledge_pipeline_product.id）',
    status              VARCHAR(32) NOT NULL DEFAULT 'QUEUED' COMMENT '状态：QUEUED/RUNNING/SUCCESS/PARTIAL_SUCCESS/FAILED',
    error_code          VARCHAR(64)          DEFAULT NULL   COMMENT '错误码',
    error_msg           VARCHAR(1024)        DEFAULT NULL   COMMENT '错误信息',
    create_time         DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    started_at          DATETIME(3)          DEFAULT NULL   COMMENT '开始执行时间',
    finished_at         DATETIME(3)          DEFAULT NULL   COMMENT '结束时间',
    PRIMARY KEY (id),
    -- 执行唯一键：同一份输入 + 同一策略只能有一条任务（0 表示"没有上游"与"无策略"两档，避开 NULL 不参与唯一比较）
    -- 键包含 stage，与应用侧 getByExecKey(fileResultId, stage, 上游, 策略) 的查询键完全对齐
    -- 知识库级 BUILD_INDEX 任务的 file_result_id 为空，NULL 不参与唯一比较，不在本约束范围内
    UNIQUE KEY uk_exec (file_result_id, stage, upstream_product_id, strategy_version_id),
    KEY idx_fr_stage (file_result_id, stage),
    KEY idx_status (status)
)  COMMENT ='处理链任务：一次触发一条任务（机器表 append-only）';

CREATE TABLE wb_knowledge_pipeline_product
(
    id                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键（雪花，应用显式传 id）',
    task_id             BIGINT       NOT NULL                COMMENT '产出该产物的任务（一次执行至多一份产物）',
    file_result_id      BIGINT       NOT NULL                COMMENT '所属文件结果',
    stage               VARCHAR(32)  NOT NULL                COMMENT '产出环节（PipelineStage 枚举名）',
    upstream_product_id BIGINT                DEFAULT NULL   COMMENT '上游产物 ID（可空）',
    capability_snapshot varchar(1024)                  DEFAULT NULL   COMMENT '能力/策略版本快照',
    artifact_id         VARCHAR(255) NOT NULL                COMMENT '产物存储引用（sha256 寻址 key）',
    storage_type        VARCHAR(32)  NOT NULL                COMMENT '存储类型（StorageType 枚举码：minio / local；冗余留作展示与降级）',
    storage_source_id   BIGINT       NOT NULL                COMMENT '数据源实例 ID（wb_storage_source.id）：读取按它定位后端',
    bucket              VARCHAR(64)  NOT NULL                COMMENT '存储桶名（local 下为一级子目录）',
    content_hash        CHAR(64)     NOT NULL                COMMENT '产物内容指纹（sha256）',
    create_time         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (id),
    -- 一次执行（一条任务）至多一份产物：重复执行按既有行复用，不新增行
    UNIQUE KEY uk_product_task (task_id),
    KEY idx_fr_stage (file_result_id, stage)
)  COMMENT ='阶段产物：本体在产物存储，表内存引用与血缘（机器表 append-only）';

CREATE TABLE wb_knowledge_pipeline_step_log
(
    id                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键（雪花，应用显式传 id）',
    task_id            BIGINT       NOT NULL                COMMENT '所属处理链任务（wb_knowledge_pipeline_task.id）',
    step_name          VARCHAR(64)  NOT NULL                COMMENT '子步骤名：文件级路由/原生解析/质量检查…',
    status             VARCHAR(32)  NOT NULL                COMMENT '步骤状态：SUCCESS/FAILED',
    capability_version VARCHAR(64)           DEFAULT NULL   COMMENT '能力/模型版本（如 pdfbox-3.0.4）',
    input_ref          BIGINT                DEFAULT NULL   COMMENT '输入产物引用（可空）',
    output_ref         BIGINT                DEFAULT NULL   COMMENT '输出产物引用（可空）',
    started_at         DATETIME(3)           DEFAULT NULL   COMMENT '开始时间',
    finished_at        DATETIME(3)           DEFAULT NULL   COMMENT '结束时间',
    duration           INT                   DEFAULT NULL   COMMENT '耗时（毫秒）',
    warning_count      INT          NOT NULL DEFAULT 0      COMMENT '告警数',
    matched_count      INT          NOT NULL DEFAULT 0      COMMENT '命中数（预处理规则命中次数）',
    changed_count      INT          NOT NULL DEFAULT 0      COMMENT '变更数（实际改写次数）',
    avg_len            INT          NOT NULL DEFAULT 0      COMMENT '平均长度（字符；切片子步骤统计）',
    error              VARCHAR(1024)         DEFAULT NULL   COMMENT '错误信息',
    create_time        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_task (task_id)
)  COMMENT ='子步骤记录：环节内每子步骤一条（机器表 append-only）';

-- ============================================================================
-- 四、环节策略版本与知识库绑定
-- ============================================================================

CREATE TABLE wb_knowledge_pipeline_strategy_version
(
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键（雪花，应用显式传 id）',
    type            VARCHAR(32)  NOT NULL                COMMENT '策略类型：PREPROCESS/CHUNK/EMBED/RETRIEVAL',
    name            VARCHAR(128) NOT NULL                COMMENT '策略名',
    version         VARCHAR(32)  NOT NULL                COMMENT '版本号',
    config_snapshot varchar(1024)         NOT NULL                COMMENT '配置快照（完整参数）',
    status          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/INACTIVE',
    create_time     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY idx_type_name_version (type, name, version)
)  COMMENT ='环节策略版本（不可变，修改即新版本）';

CREATE TABLE wb_knowledge_strategy_binding
(
    id                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键（雪花，应用显式传 id）',
    knowledge_base_id   BIGINT       NOT NULL COMMENT '知识库 ID',
    strategy_type       VARCHAR(32)  NOT NULL COMMENT '策略类型：PREPROCESS/CHUNK/EMBED',
    strategy_version_id BIGINT       NOT NULL COMMENT '策略版本行 ID（wb_knowledge_pipeline_strategy_version.id）',
    del_flag            VARCHAR(1)   NOT NULL DEFAULT '0' COMMENT '删除标记：0 正常 / 1 已删（解绑）',
    create_by           VARCHAR(64)           DEFAULT NULL COMMENT '创建人',
    create_time         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    update_by           VARCHAR(64)           DEFAULT NULL COMMENT '更新人',
    update_time         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY idx_kb_type (knowledge_base_id, strategy_type)
)  COMMENT ='知识库-策略绑定：上层应用接入后按知识库直接取用绑定策略';

-- ============================================================================
-- 五、切片与向量化
-- ============================================================================

CREATE TABLE wb_knowledge_chunk_set
(
    id                     BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键（雪花，应用显式传 id）',
    file_result_id         BIGINT       NOT NULL                COMMENT '所属文件结果',
    upstream_product_id    BIGINT       NOT NULL                COMMENT '上游产物（预处理视图产物）',
    chunk_strategy_version VARCHAR(32)  NOT NULL                COMMENT '切片策略版本（版本号字符串）',
    chunk_count            INT          NOT NULL DEFAULT 0      COMMENT '切片数',
    total_chars            INT          NOT NULL DEFAULT 0      COMMENT '总字符数',
    status                 VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态',
    artifact_id            VARCHAR(255) NOT NULL                COMMENT 'ChunkSet 归档 JSON 引用（sha256）',
    storage_type           VARCHAR(32)  NOT NULL                COMMENT '存储类型（StorageType 枚举码：minio / local；冗余留作展示与降级）',
    storage_source_id      BIGINT       NOT NULL                COMMENT '数据源实例 ID（wb_storage_source.id）：读取按它定位后端',
    bucket                 VARCHAR(64)  NOT NULL                COMMENT '存储桶名（local 下为一级子目录）',
    create_time            DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_fr (file_result_id)
)  COMMENT ='切片产物集合（一次切片策略运行；机器表 append-only）';

CREATE TABLE wb_knowledge_chunk
(
    id                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键（雪花，应用显式传 id）',
    chunk_set_id       BIGINT       NOT NULL                COMMENT '所属切片集合',
    chunk_id           VARCHAR(64)  NOT NULL                COMMENT '集合内顺序号（集合内唯一）',
    parent_chunk_id    VARCHAR(64)           DEFAULT NULL   COMMENT '父片（父子层级）',
    content            MEDIUMTEXT   NOT NULL                COMMENT '切片内容（normalizedText 口径）',
    content_type       VARCHAR(32)  NOT NULL                COMMENT '内容类型：SECTION/PARAGRAPH/TABLE/IMAGE/FALLBACK',
    title_path         VARCHAR(512)          DEFAULT NULL   COMMENT '标题路径（最多 3 级）',
    page_range         VARCHAR(1024)          DEFAULT NULL   COMMENT '页码范围（如 1-3；连续段压缩、多段逗号连接，无损不截断）',
    table_ref          VARCHAR(64)           DEFAULT NULL   COMMENT '表格引用（统一文档模型表元素 ID）',
    order_no           INT          NOT NULL DEFAULT 0      COMMENT '集合内顺序',
    char_count         INT          NOT NULL DEFAULT 0      COMMENT '字符数',
    token_count        INT          NOT NULL DEFAULT 0      COMMENT 'Token 估算（字符数÷1.5）',
    strategy_version   VARCHAR(32)           DEFAULT NULL   COMMENT '切片策略版本（冗余）',
    create_time        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_set (chunk_set_id),
    KEY idx_parent (chunk_set_id, parent_chunk_id)
)  COMMENT ='切片：检索命中的最小单元（机器表 append-only）';

CREATE TABLE wb_knowledge_embedding_set
(
    id               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键（雪花，应用显式传 id）',
    file_result_id   BIGINT        NOT NULL COMMENT '所属文件结果',
    chunk_set_ref    BIGINT        DEFAULT NULL COMMENT '上游切片集合引用（wb_knowledge_chunk_set.id）',
    embedding_set_id VARCHAR(128)  NOT NULL COMMENT '集合ID（es-{chunkSetId}-{strategyVersion}，确定性）',
    strategy_version VARCHAR(64)   NOT NULL COMMENT 'EMBED 策略版本（如 embed-default-v1）',
    model            VARCHAR(128)  NOT NULL COMMENT '模型名（模型目录口径）',
    dimension        INT           NOT NULL COMMENT '向量维度（目录冗余锁定）',
    metric           VARCHAR(16)   NOT NULL COMMENT '度量：COSINE/IP/L2（目录冗余锁定）',
    normalized       TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '是否归一化（目录冗余锁定）',
    record_count     INT           NOT NULL DEFAULT 0 COMMENT '记录数（含复用/跳过）',
    cached_count     INT           NOT NULL DEFAULT 0 COMMENT '复用命中数',
    status           VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE',
    artifact_id      VARCHAR(255)  NOT NULL COMMENT 'EmbeddingSet 归档 JSON 引用（sha256）',
    storage_type     VARCHAR(32)   NOT NULL COMMENT '存储类型（StorageType 枚举码：minio / local；冗余留作展示与降级）',
    storage_source_id BIGINT       NOT NULL COMMENT '数据源实例 ID（wb_storage_source.id）：读取按它定位后端',
    bucket           VARCHAR(64)   NOT NULL COMMENT '存储桶名（local 下为一级子目录）',
    create_time      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_fr (file_result_id),
    KEY idx_fr_sv (file_result_id, strategy_version)
)  COMMENT ='向量产物集合：一次 EMBED 运行一行；本体在产物存储，本表存引用与血缘';

CREATE TABLE wb_knowledge_embedding_record
(
    id               BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键（雪花，应用显式传 id）',
    embedding_set_id BIGINT      NOT NULL COMMENT '所属集合（wb_knowledge_embedding_set.id）',
    embedding_id     VARCHAR(32) NOT NULL COMMENT '记录ID（emb-0001，集合内唯一）',
    chunk_id         VARCHAR(32) NOT NULL COMMENT '切片ID（Chunk.chunkId）',
    content_type     VARCHAR(32) DEFAULT NULL COMMENT '切片内容类型（ChunkContentType 枚举名）',
    parent_chunk_id  VARCHAR(32) DEFAULT NULL COMMENT '父片ID（父子检索）',
    input_text       MEDIUMTEXT  COMMENT '编码输入文本（=chunk.content 原样）',
    input_text_hash  VARCHAR(64) DEFAULT NULL COMMENT '输入文本指纹（sha256 hex，复用键）',
    token_count      INT         NOT NULL DEFAULT 0 COMMENT 'Token 估算（ceil(字符数÷1.5)）',
    request_id       VARCHAR(64) DEFAULT NULL COMMENT '网关 requestId（复用命中时为空）',
    status           VARCHAR(16) NOT NULL COMMENT '状态：SUCCESS/CACHED/SKIPPED/FAILED',
    cache_hit        TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '是否账本复用命中',
    create_time      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_set (embedding_set_id)
)  COMMENT ='向量记录：一个切片一条；向量本体只进集合文件，不进 DB';

-- ============================================================================
-- 六、索引构建与发布
-- ============================================================================

CREATE TABLE wb_knowledge_index_set
(
    id                           BIGINT      NOT NULL COMMENT '主键（雪花）',
    knowledge_base_id            BIGINT      NOT NULL COMMENT '所属知识库',
    current_published_version_id BIGINT               DEFAULT NULL COMMENT '当前发布版本 ID（二级发布指针；检索环节读取）',
    create_time                  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY idx_kb (knowledge_base_id)
) ENGINE = InnoDB
   COMMENT ='索引集合：一知识库一行，承载二级发布指针';

CREATE TABLE wb_knowledge_index_version
(
    id              BIGINT        NOT NULL COMMENT '主键（雪花）',
    index_set_id    BIGINT        NOT NULL COMMENT '所属索引集合',
    version_no      VARCHAR(32)   NOT NULL COMMENT '版本号（v1、v2…；= 组合注册序号，集合名 kb_{kbId}_{versionNo} 由它构成）',
    combo_snapshot  varchar(1024)          NOT NULL COMMENT '组合快照（fileScope/shape/stageStrategies：stage → 策略 name-version 映射）',
    chunk_count     INT           NOT NULL DEFAULT 0 COMMENT '纳入片数（活账本：随追加持续更新）',
    vector_count    INT           NOT NULL DEFAULT 0 COMMENT '纳入向量数（活账本）',
    status          VARCHAR(32)   NOT NULL DEFAULT 'CREATED' COMMENT '状态：CREATED/BUILDING/READY/ONLINE/FAILED/RETIRED',
    default_rule_id BIGINT                 DEFAULT NULL COMMENT '默认检索规则（wb_knowledge_pipeline_strategy_version.id，type=RETRIEVAL）',
    build_error     VARCHAR(1024)          DEFAULT NULL COMMENT '失败原因（完整性缺口/维度不一致/一致性失败/写失败）',
    task_id         BIGINT                 DEFAULT NULL COMMENT '构建任务 ID（wb_knowledge_pipeline_task，stage=BUILD_INDEX）',
    validated_at    DATETIME(3)            DEFAULT NULL COMMENT '验证通过时间',
    published_at    DATETIME(3)            DEFAULT NULL COMMENT '发布时间',
    published_by    VARCHAR(64)            DEFAULT NULL COMMENT '发布人',
    retired_at      DATETIME(3)            DEFAULT NULL COMMENT '退役时间',
    retired_by      VARCHAR(64)            DEFAULT NULL COMMENT '退役人',
    create_time     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY idx_set_version (index_set_id, version_no)
)  COMMENT ='索引版本行：组合快照 + 状态机 + 发布/退役留痕';

-- ============================================================================
-- 七、检索运行记录
-- ============================================================================

CREATE TABLE wb_knowledge_retrieval_run
(
    id                BIGINT      NOT NULL COMMENT '主键（雪花）',
    kb_id             BIGINT      NOT NULL COMMENT '知识库 ID',
    version_id        BIGINT      NOT NULL COMMENT '索引版本行 ID',
    version_no        VARCHAR(32) NOT NULL COMMENT '版本号（快照冗余，展示用）',
    rule_id           BIGINT               DEFAULT NULL COMMENT '规则行 ID（wb_knowledge_pipeline_strategy_version；引擎基线规则无行 ID 时为 NULL）',
    rule_name_version VARCHAR(64) NOT NULL COMMENT '规则 name-version（快照冗余，展示用）',
    query             TEXT        NOT NULL COMMENT '查询文本',
    result_snapshot   JSON        NOT NULL COMMENT '结果快照（命中列表全字段，执行时刻快照=评测证据）',
    elapsed_ms        INT         NOT NULL DEFAULT 0 COMMENT '耗时（毫秒）',
    create_time       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_kb_version_rule (kb_id, version_id, rule_id),
    KEY idx_create_time (create_time)
)  COMMENT ='检索运行记录：四元组 + 执行时刻快照（评测原始数据；机器表 append-only）';

-- ============================================================================
-- 八、用户
-- ============================================================================

CREATE TABLE wb_user
(
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键（雪花，应用显式传 id）',
    username      VARCHAR(64)  NOT NULL                COMMENT '登录用户名',
    display_name  VARCHAR(64)  NOT NULL DEFAULT ''     COMMENT '真实姓名',
    email         VARCHAR(128)          DEFAULT NULL   COMMENT '邮箱',
    phone         VARCHAR(32)           DEFAULT NULL   COMMENT '手机号',
    avatar        VARCHAR(255)          DEFAULT NULL   COMMENT '头像对象存储的公开 URL；未设置头像为空',
    avatar_storage_type      VARCHAR(32)      DEFAULT NULL   COMMENT '头像存储类型（StorageType 枚举码：minio / local）；未设置头像为空',
    avatar_storage_source_id BIGINT           DEFAULT NULL   COMMENT '头像数据源实例 ID（wb_storage_source.id）；未设置头像为空',
    avatar_bucket            VARCHAR(64)      DEFAULT NULL   COMMENT '头像桶名（local 下为一级子目录）；未设置头像为空',
    password      VARCHAR(128) NOT NULL                COMMENT '密码（BCrypt 哈希）',
    status        TINYINT      NOT NULL DEFAULT 1      COMMENT '状态：1 启用 / 0 停用',
    role          VARCHAR(32)  NOT NULL DEFAULT 'USER' COMMENT '角色码值：ADMIN 管理员 / USER 普通用户',
    token_version INT          NOT NULL DEFAULT 0      COMMENT '令牌版本：递增即让已签发令牌失效',
    del_flag      VARCHAR(1)   NOT NULL DEFAULT '0'    COMMENT '删除标记：0 正常 / 1 已删',
    create_by     VARCHAR(64)           DEFAULT NULL   COMMENT '创建人',
    create_time   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    update_by     VARCHAR(64)           DEFAULT NULL   COMMENT '更新人',
    update_time   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username),
    KEY idx_del_flag (del_flag)
) COMMENT ='用户：登录账号';

-- ============================================================================
-- 九、系统设置与存储数据源
-- ============================================================================

CREATE TABLE wb_system_setting
(
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键（雪花，应用显式传 id）',
    setting_key   VARCHAR(64)  NOT NULL COMMENT '配置键（SystemSettingKey 枚举名）',
    setting_value VARCHAR(255) NOT NULL COMMENT '配置值（字符串，取值域由对应领域服务校验）',
    create_by     VARCHAR(64)           DEFAULT NULL COMMENT '创建人',
    create_time   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    update_by     VARCHAR(64)           DEFAULT NULL COMMENT '最后修改人',
    update_time   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_setting_key (setting_key)
)  COMMENT ='系统设置：键值形态；新增设置只加枚举与领域服务，表结构不动';

CREATE TABLE wb_storage_source
(
    id           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键（雪花，应用显式传 id）',
    name         VARCHAR(64)   NOT NULL COMMENT '数据源名称（界面展示，全表唯一）',
    storage_type VARCHAR(32)   NOT NULL COMMENT '存储类型（StorageType 枚举码：minio / local）',
    config_json  VARCHAR(2048) NOT NULL COMMENT '连接参数（JSON，按类型定义；含密钥，接口按原值返回）',
    is_current   TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '是否当前启用：1 是 / 0 否（全表至多一行 1）',
    status       VARCHAR(16)   NOT NULL DEFAULT 'ENABLED' COMMENT '状态：ENABLED 启用 / DISABLED 停用',
    last_probe_ok TINYINT(1)            DEFAULT NULL COMMENT '最近一次连接探测结果：1 成功 / 0 失败 / NULL 未探测过',
    last_probe_at DATETIME(3)           DEFAULT NULL COMMENT '最近一次连接探测时间（NULL = 未探测过）',
    del_flag     VARCHAR(1)    NOT NULL DEFAULT '0' COMMENT '删除标记：0 正常 / 1 已删',
    create_by    VARCHAR(64)            DEFAULT NULL COMMENT '创建人',
    create_time  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    update_by    VARCHAR(64)            DEFAULT NULL COMMENT '更新人',
    update_time  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_name (name),
    KEY idx_del_flag (del_flag)
)  COMMENT ='存储数据源：一行 = 一个数据源实例（含连接参数）；当前启用的一行决定新对象的写入后端';
-- ============================================================================
-- 十、模型目录
-- ============================================================================

-- 模型目录：一行 = 一个可调用的模型。厂商连接与密钥在配置（knowledge.model.vendors.*），本表只存厂商码。
-- capabilities 为多值（逗号分隔，取值 CHAT/EMBEDDING/OCR/RERANK/IMAGE/VIDEO），候选集按能力筛，限额按模型行算。
CREATE TABLE wb_model
(
    id                        BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键（雪花，应用显式传 id）',
    vendor_code               VARCHAR(64)    NOT NULL COMMENT '厂商码（knowledge.model.vendors 配置里的键）',
    model_key                 VARCHAR(128)   NOT NULL COMMENT '厂商侧模型名（请求体里的模型标识）',
    capabilities              VARCHAR(255)   NOT NULL COMMENT '能力清单（多值，逗号分隔：CHAT/EMBEDDING/OCR/RERANK/IMAGE/VIDEO）',
    thinking_enabled          TINYINT        NOT NULL DEFAULT 0 COMMENT '是否支持思考模式：1 支持 / 0 不支持（仅登记能力，不装配请求参数）',
    input_limit               INT                     DEFAULT NULL COMMENT '最大输入长度（tokens，可空）',
    output_limit              INT                     DEFAULT NULL COMMENT '最大输出长度（tokens，可空）',
    thinking_input_limit      INT                     DEFAULT NULL COMMENT '思考模式下的最大输入长度（tokens，可空）',
    thinking_output_limit     INT                     DEFAULT NULL COMMENT '思考模式下的最大输出长度（tokens，可空）',
    context_window            INT                     DEFAULT NULL COMMENT '上下文长度（tokens，可空）',
    price_in_per_m            DECIMAL(18, 6)          DEFAULT NULL COMMENT '输入每百万 token 单价·缓存未命中（可空；对应能力 CHAT）',
    price_in_per_m_cache_hit  DECIMAL(18, 6)          DEFAULT NULL COMMENT '输入每百万 token 单价·缓存命中（可空；对应能力 CHAT）',
    price_out_per_m           DECIMAL(18, 6)          DEFAULT NULL COMMENT '输出每百万 token 单价（可空；对应能力 CHAT）',
    currency                  VARCHAR(8)     NOT NULL DEFAULT 'CNY' COMMENT '币种：CNY / USD',
    rpm_limit                 INT            NOT NULL COMMENT '每分钟请求数上限',
    tpm_limit                 INT            NOT NULL COMMENT '每分钟 token 上限',
    status                    VARCHAR(16)    NOT NULL DEFAULT 'ENABLED' COMMENT '状态：ENABLED 启用 / DISABLED 停用',
    del_flag                  VARCHAR(1)     NOT NULL DEFAULT '0' COMMENT '删除标记：0 正常 / 1 已删',
    create_by                 VARCHAR(64)             DEFAULT NULL COMMENT '创建人',
    create_time               DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    update_by                 VARCHAR(64)             DEFAULT NULL COMMENT '更新人',
    update_time               DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_vendor_model (vendor_code, model_key),
    KEY idx_status_capabilities (status, capabilities),
    KEY idx_del_flag (del_flag)
) ENGINE = InnoDB
   COMMENT ='模型目录：一行 = 一个模型的厂商归属、能力、长度、价格与限额';

-- ============================================================================
-- 模型调用明细：一次尝试一行，成功失败都记，只插不改
-- ============================================================================
CREATE TABLE wb_model_call
(
    id                BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    request_id        VARCHAR(64) NOT NULL COMMENT '一次业务请求的追踪 ID（同一次请求的多次尝试共用）',
    task_id           BIGINT               DEFAULT NULL COMMENT '处理链任务 ID（可空）',
    capability        VARCHAR(32) NOT NULL COMMENT '能力（ModelCapability）',
    vendor_code       VARCHAR(64) NOT NULL COMMENT '厂商码',
    model_key         VARCHAR(128) NOT NULL COMMENT '厂商侧模型名',
    route_reason      VARCHAR(32) NOT NULL COMMENT 'RouteReason',
    attempt           INT         NOT NULL COMMENT '本次请求内的尝试序号（1 起）',
    status            VARCHAR(16) NOT NULL COMMENT 'SUCCESS / FAILED',
    error_code        INT                  DEFAULT NULL COMMENT '失败时的错误码',
    latency_ms        INT         NOT NULL COMMENT '本模型调用耗时',
    prompt_tokens     INT                  DEFAULT NULL COMMENT '输入 token（usage 回填）',
    completion_tokens INT                  DEFAULT NULL COMMENT '输出 token（usage 回填）',
    create_time       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_model_time (vendor_code, model_key, create_time),
    KEY idx_request (request_id)
) ENGINE = InnoDB
   COMMENT ='模型调用明细：一次尝试一行，成功失败都记';

-- ============================================================================
-- ============================================================================
-- 十一、初始数据
-- ============================================================================
-- ============================================================================

-- 管理员账号：用户名 admin / 密码 Admin@123（BCrypt 哈希），首次登录后请立即改密
INSERT INTO wb_user (id, username, display_name, password, status, role, token_version, del_flag)
VALUES (1, 'admin', '管理员', '$2a$10$Z/lASt5WwaaR.Y84fMsDkeoUtIxb4jiniyYaH5AmkTyh1oCVJc8Hq', 1, 'ADMIN', 1, '0');

-- 预处理策略：页眉页脚/目录/噪声只标记，重复只留首份，字段/文本整理/编码开（策略版本行的初始参数，与代码侧模板同参）
INSERT INTO wb_knowledge_pipeline_strategy_version (type, name, version, config_snapshot, status)
VALUES ('PREPROCESS', 'preproc-default', 'v1',
        '{"rules":{"headerFooter":{"action":"MARK"},"toc":{"action":"MARK","params":{"minLinesPerPage":"3","runMinLength":"3"}},"noise":{"action":"MARK"},"repeat":{"action":"EXCLUDE"},"field":{"enabled":"ON","params":{"amount":"ON","date":"ON","area":"ON","certNo":"ON"}},"tidy":{"enabled":"ON","params":{"whitespace":"ON","joinLines":"ON","punct":"ON","dashes":"ON","bullets":"ON","urls":"ON"}},"encoding":{"enabled":"ON"}},"custom":{"enabled":"ON","rules":[]}}',
        'ACTIVE');

-- 切片策略：段落聚合 + 行级表切片 + 递归兜底（策略版本行的初始参数，与代码侧模板同参）
INSERT INTO wb_knowledge_pipeline_strategy_version (type, name, version, config_snapshot, status)
VALUES ('CHUNK', 'chunk-hybrid', 'v1',
        '{"routes":{"body":{"algorithm":"paragraph-aggregate","params":{"targetMaxLen":"800","softMaxLen":"1000"}},"table":{"algorithm":"row-slice","params":{"groupThreshold":"30","groupSize":"3"}},"image":{"algorithm":"caption-placeholder"},"fallback":{"algorithm":"recursive-length","params":{"len":"500","overlap":"50"}}},"pipeline":{"titlePathMaxLevel":"3","parentChild":"ON","tableInBodyFlow":"OFF","minMergeLen":"300","structureOverlap":"0","titleInContent":"ON"}}',
        'ACTIVE');

-- 向量化策略：默认模型 + 账本复用开（与内置默认同参）
INSERT INTO wb_knowledge_pipeline_strategy_version (type, name, version, config_snapshot, status)
VALUES ('EMBED', 'embed-default', 'v1',
        '{"model":"text-embedding-v4","docTemplate":"{content}","queryTemplate":"{query}","batchSize":32,"timeoutMs":30000,"maxRetries":2,"cacheEnabled":"ON","includeParent":"OFF","skipEmpty":"ON","dimension":1024,"metric":"COSINE","normalized":true,"contextWindowTokens":8192,"batchLimit":64}',
        'ACTIVE');

-- 检索规则：混合 RRF + 父片展开（与引擎基线同参）
INSERT INTO wb_knowledge_pipeline_strategy_version (type, name, version, config_snapshot, status)
VALUES ('RETRIEVAL', 'hybrid-rrf-k60-top10-parent', 'v1',
        '{"channel":"HYBRID","fusion":{"mode":"RRF","rrfK":60,"perChannelLimit":50},"preprocess":{"mode":"NONE"},"rerank":{"mode":"NONE"},"postprocess":{"mode":"PARENT_EXPAND"},"topK":10,"scoreThreshold":0}',
        'ACTIVE');

-- 存储数据源：MinIO 与本地磁盘两行；当前启用写在 is_current 上（全表至多一行为 1）
-- 两列探测结论留空表示尚未探测，应用启动时探活当前启用那行并回写
-- 表为空或没有可用当前行时应用启动失败，不在运行期按配置补行
-- MinIO 行凭据与 docker-compose 的 MinIO 根凭据一致（默认 minioadmin 仅供本机开发，COMMON-SEC-01 边界）；
-- 用环境变量覆盖 compose 凭据时，本行必须同步修改
INSERT INTO wb_storage_source (id, name, storage_type, config_json, is_current, status, del_flag)
VALUES (1, 'MinIO', 'minio',
        '{"endpoint":"http://localhost:9000","accessKey":"minioadmin","secretKey":"minioadmin","fileBucket":"files","artifactBucket":"artifacts"}',
        1, 'ENABLED', '0'),
       (2, '本地磁盘', 'local',
        '{"rootDir":"./data/file-center","fileDir":"files","artifactDir":"artifacts"}',
        0, 'ENABLED', '0');
