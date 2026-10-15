package com.workbuddy.common.enums.task;

/**
 * 任务错误码：落 kb_pipeline_task.error_code，与 API 错误码（ErrorCode 40xxx）分层。
 *
 * @author cxxl
 */
public enum PipelineTaskErrorCode {

    /** 任务入队失败（Redis 队列写不进去；任务已落库但没排上队，可重新触发） */
    TASK_ENQUEUE_FAILED,

    /** 任务未指定上游产物（任务行缺 upstreamProductId 引用） */
    TASK_UPSTREAM_MISSING,

    /** 执行超时（孤儿恢复判定） */
    EXECUTOR_TIMEOUT,

    /** 服务停机中断（停机时仍在执行的任务被显式置失败） */
    TASK_SHUTDOWN_INTERRUPTED,

    /** 任务所属数据源与当前启用的数据源不一致（排队/重跑期间切换了当前启用，产物写入被拒） */
    TASK_STORAGE_MISMATCH,

    /** 文件深层损坏（入口探测未发现，解析时暴露） */
    PARSE_CORRUPTED,

    /** 扫描件暂不支持（OCR 预留，暂未接入） */
    SCANNED_UNSUPPORTED,

    /** 成功单元占比低于门槛（90%） */
    RATIO_BELOW_THRESHOLD,

    /** 解析执行异常兜底 */
    PARSE_FAILED,

    /** 组装空树（无任何可组装元素） */
    STRUCTURE_EMPTY,

    /** 组装上游产物不可读（产物引用缺失、对象读不到、反序列化失败） */
    STRUCTURE_UPSTREAM_UNREADABLE,

    /** 组装执行异常兜底 */
    STRUCTURE_FAILED,

    /** 预处理空输入（上游统一结构无任何可处理元素/上游产物缺失） */
    PREPROCESS_EMPTY,

    /** 预处理执行异常兜底 */
    PREPROCESS_FAILED,

    /** 切片空集（上游预处理视图无任何可切片元素/上游产物缺失） */
    CHUNK_EMPTY,

    /** 切片执行异常兜底 */
    CHUNK_FAILED,

    /** 向量化空输入（上游切片产物缺失/为空） */
    EMBED_EMPTY,

    /** 切片最大片长超过模型窗口（前置校验不通过） */
    EMBED_MODEL_INCOMPATIBLE,

    /** 向量一致性校验失败（四关拦截） */
    EMBED_CONSISTENCY_FAILED,

    /** 向量化执行异常兜底 */
    EMBED_FAILED,

    /** 索引构建：组合产物不完整（缺口明细见 kb_index_version.build_error） */
    INDEX_INCOMPLETE,

    /** 索引构建：向量维度不一致（跨产物/与 collection 维度冲突） */
    INDEX_DIMENSION_MISMATCH,

    /** 索引构建：一致性校验失败（本版本 chunkId 与产物集合不一致，已清候选） */
    INDEX_CONSISTENCY_FAILED,

    /** 索引构建执行异常兜底（Milvus 写失败等） */
    INDEX_BUILD_FAILED
}
