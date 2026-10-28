package com.workbuddy.worker.indexing.search;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 全文检索命中：TEXT_MATCH 匹配召回（无 BM25 分数，score 恒为 null）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FullTextHit implements IndexHit {

    /** 片 ID */
    private String chunkId;

    /** 文件结果 ID */
    private Long documentId;

    /** 片类型 */
    private String contentType;

    /** 父片 ID */
    private String parentChunkId;

    /** 片文本 */
    private String content;

    /** 标题路径 */
    private String titlePath;

    /** 源元素 ID（JSON 串） */
    private String sourceElementIds;
}
