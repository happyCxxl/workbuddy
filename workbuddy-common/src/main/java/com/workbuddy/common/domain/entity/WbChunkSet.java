package com.workbuddy.common.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 切片产物集合：一次切片策略运行的产物集合（多套策略 = 多个集合并存）。
 * 机器表：后台线程写入、append-only、不逻辑删——不挂平台五件套（仅 create_time）。
 */
@Data
@TableName("wb_knowledge_chunk_set")
public class WbChunkSet {

    /** 主键 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属文件结果 */
    private Long fileResultId;

    /** 上游产物（预处理视图产物） */
    private Long upstreamProductId;

    /** 切片策略版本（版本号字符串） */
    private String chunkStrategyVersion;

    /** 切片数 */
    private Integer chunkCount;

    /** 总字符数 */
    private Integer totalChars;

    /** 状态（ACTIVE） */
    private String status;

    /** ChunkSet 归档 JSON 引用（sha256） */
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
