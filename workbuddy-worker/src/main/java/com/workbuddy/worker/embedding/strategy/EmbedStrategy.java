package com.workbuddy.worker.embedding.strategy;

import com.workbuddy.worker.StageStrategy;

import lombok.Data;

/**
 * 向量化策略快照（结构化模型，与 CHUNK/PREPROCESS 同构）：
 * type/name/version + 模型 + 模板 + 批量/调用参数 + 执行口径开关 + 目录冗余（维度/度量/归一化/窗口/批上限）。
 * 触发时快照进任务，执行只用快照（可复现）。
 * 目录冗余字段由 {@link EmbedAlgorithmSpec#normalize} 从模型目录带出并锁定（保存时已冗余进快照）。
 *
 * @author cxxl
 */
@Data
public class EmbedStrategy implements StageStrategy {

    /** 策略类型 */
    public static final String TYPE = "EMBED";

    /** 开关值（JSON 契约） */
    public static final String ON = "ON";
    public static final String OFF = "OFF";

    /** 策略类型（EMBED） */
    private String type = TYPE;

    /** 策略名 */
    private String name;

    /** 版本号 */
    private String version;

    /** 模型名（模型目录口径，必选） */
    private String model;

    /** 文档侧模板（{content} 必含；当前行为原样编码） */
    private String docTemplate;

    /** 查询侧模板（{query} 必含；检索环节用） */
    private String queryTemplate;

    /** 批量大小（1~128） */
    private Integer batchSize;

    /** 单批超时（毫秒，1000~120000） */
    private Integer timeoutMs;

    /** 最大重试次数（0~5） */
    private Integer maxRetries;

    /** KV 复用开关（ON/OFF；OFF=跳过复用判定强制重算，对比实验） */
    private String cacheEnabled;

    /** 父片向量化开关（默认 OFF，只向量化子片） */
    private String includeParent;

    /** 空文本跳过（ON/OFF） */
    private String skipEmpty;

    // ---------------- 目录冗余（模型目录带出，快照锁定） ----------------

    /** 向量维度 */
    private Integer dimension;

    /** 度量（EmbedMetric.key()） */
    private String metric;

    /** 是否归一化 */
    private Boolean normalized;

    /** 上下文窗口（tokens） */
    private Integer contextWindowTokens;

    /** 单批上限（条；预留未消费——实际拆批按 batchSize、保存校验按硬编码上限） */
    private Integer batchLimit;

    /** 复用开关判定（ON 大小写不敏感） */
    public boolean cacheOn() {
        return ON.equalsIgnoreCase(cacheEnabled);
    }

    /** 父片开关判定（默认 false） */
    public boolean includeParentOn() {
        return ON.equalsIgnoreCase(includeParent);
    }

    /** 空文本跳过判定 */
    public boolean skipEmptyOn() {
        return ON.equalsIgnoreCase(skipEmpty);
    }

    /** 完整版本串（name-version，如 embed-default-v1） */
    public String fullVersion() {
        return name + "-" + version;
    }
}
