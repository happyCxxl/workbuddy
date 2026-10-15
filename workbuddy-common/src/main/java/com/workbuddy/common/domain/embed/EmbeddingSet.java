package com.workbuddy.common.domain.embed;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 向量产物集合：一次 EMBED 策略运行的产物（多套策略 = 多个集合并存，平行候选）。
 * 每次运行**完整自包含**（复用 + 新算全量 record，向量本体完整复制，不存指针）。
 *
 * @author cxxl
 */
@Data
public class EmbeddingSet {

    /** 集合 ID = "es-" + chunkSetId + "-" + strategyVersion（确定性） */
    private String embeddingSetId;

    /** 文件结果 ID（wb_file_result.id） */
    private Long fileResultId;

    /** 上游切片集合引用（wb_knowledge_chunk_set.id） */
    private Long chunkSetRef;

    /** 上游切片集合 ID（ChunkSet.chunkSetId，血缘） */
    private String chunkSetId;

    /** EMBED 策略版本（如 embed-default-v1） */
    private String strategyVersion;

    /** 模型名（目录口径） */
    private String model;

    /** 向量维度（目录冗余锁定） */
    private int dimension;

    /** 度量（EmbedMetric.key()，目录冗余锁定） */
    private String metric;

    /** 是否归一化（目录冗余锁定） */
    private boolean normalized;

    /** 记录数（含复用/跳过） */
    private int recordCount;

    /** 复用命中数（cacheHit=true） */
    private int cachedCount;

    /** 向量记录列表（文档顺序） */
    private List<EmbeddingRecord> records = new ArrayList<>();
}
