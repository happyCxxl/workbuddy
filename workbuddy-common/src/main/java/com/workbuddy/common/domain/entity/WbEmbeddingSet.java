package com.workbuddy.common.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 向量产物集合（wb_knowledge_embedding_set）：一次 EMBED 运行的元数据；向量本体在产物存储（EmbeddingSet JSON），本表只存引用与血缘。
 * 机器表：append-only 仅 create_time（与 wb_knowledge_chunk_set 同口径）。
 *
 * @author cxxl
 */
@Data
@TableName("wb_knowledge_embedding_set")
public class WbEmbeddingSet {

    /** 主键 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属文件结果 */
    private Long fileResultId;

    /** 上游切片集合引用（wb_knowledge_chunk_set.id） */
    private Long chunkSetRef;

    /** 集合 ID（"es-" + chunkSetId + "-" + strategyVersion，确定性） */
    private String embeddingSetId;

    /** EMBED 策略版本（如 embed-default-v1） */
    private String strategyVersion;

    /** 模型名（目录口径） */
    private String model;

    /** 向量维度（目录冗余锁定） */
    private Integer dimension;

    /** 度量（EmbedMetric.key()，目录冗余锁定） */
    private String metric;

    /** 是否归一化（目录冗余锁定） */
    private Boolean normalized;

    /** 记录数（含复用/跳过） */
    private Integer recordCount;

    /** 复用命中数 */
    private Integer cachedCount;

    /** 状态（ACTIVE） */
    private String status;

    /** 产物存储引用（sha256 寻址 key） */
    private String artifactId;

    /** 存储类型（StorageType 枚举码：minio / local；冗余留作展示与降级） */
    private String storageType;

    /** 数据源实例 ID（wb_storage_source.id）：读取按它定位后端 */
    private Long storageSourceId;

    /** 存储桶名（local 下为一级子目录） */
    private String bucket;

    /** 创建时间（DB 默认填充） */
    private LocalDateTime createTime;
}
