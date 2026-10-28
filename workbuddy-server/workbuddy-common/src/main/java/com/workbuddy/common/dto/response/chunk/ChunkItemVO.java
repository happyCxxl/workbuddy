package com.workbuddy.common.dto.response.chunk;

import lombok.Data;

/**
 * 切片项 VO：wb_knowledge_chunk 行的展示副本（检索命中最小单元）。
 */
@Data
public class ChunkItemVO {

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

    /**
     * 来源元素个数（组装环节元素，继承溯源链）。
     *
     * <p>只给个数不展开列表：父片的溯源是全部子片的并集，条数随章节大小无界增长。
     */
    private Integer sourceElementCount;

    /** 兜底原因（递归 / 固定长度降级切分时标记；非兜底片为空） */
    private String fallbackReason;
}
