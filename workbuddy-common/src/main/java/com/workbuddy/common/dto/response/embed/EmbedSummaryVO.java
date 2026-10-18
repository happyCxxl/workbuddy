package com.workbuddy.common.dto.response.embed;

import lombok.Data;

/**
 * 向量化摘要：最新 EmbeddingSet 的元数据统计。
 */
@Data
public class EmbedSummaryVO {

    /** 集合 ID */
    private String embeddingSetId;

    /** 上游切片集合 ID */
    private String chunkSetId;

    /** 策略版本 */
    private String strategyVersion;

    /** 模型名 */
    private String model;

    /** 向量维度 */
    private Integer dimension;

    /** 度量 */
    private String metric;

    /** 是否归一化 */
    private Boolean normalized;

    /** 记录数（含复用/跳过） */
    private Integer recordCount;

    /** 复用命中数 */
    private Integer cachedCount;

    /** 新算成功数 */
    private Integer successCount;

    /** 失败数 */
    private Integer failedCount;

    /** 跳过数（父片/空文本） */
    private Integer skippedCount;
}
