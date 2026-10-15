package com.workbuddy.common.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 切片：检索命中的最小单元，content = 预处理 normalizedText 口径。
 * 机器表：后台线程写入、append-only、不逻辑删——不挂平台五件套（仅 create_time）。
 *
 * @author cxxl
 */
@Data
@TableName("wb_knowledge_chunk")
public class WbChunk {

    /** 主键 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属切片集合 */
    private Long chunkSetId;

    /** 集合内切片 ID（顺序号，集合内唯一） */
    private String chunkId;

    /** 父片 ID（父子层级；父片本身为空） */
    private String parentChunkId;

    /** 切片内容（normalizedText 口径） */
    private String content;

    /** 内容类型（ChunkContentType 枚举名） */
    private String contentType;

    /** 标题路径（最多 3 级） */
    private String titlePath;

    /** 页码范围（如 1-3） */
    private String pageRange;

    /** 表格引用（表元素 ID） */
    private String tableRef;

    /** 集合内顺序 */
    private Integer orderNo;

    /** 字符数 */
    private Integer charCount;

    /** Token 估算（字符数÷1.5） */
    private Integer tokenCount;

    /** 切片策略版本（冗余记录） */
    private String strategyVersion;

    /** 创建时间（DB 默认填充） */
    private LocalDateTime createTime;
}
