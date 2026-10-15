package com.workbuddy.worker.indexing.search;

import lombok.Builder;
import lombok.Data;

/**
 * 全文检索查询：Milvus TEXT_MATCH 全文匹配（content/titlePath 配 chinese analyzer）。
 *
 * @author cxxl
 */
@Data
@Builder
public class FullTextQuery {

    /** 关键词（TEXT_MATCH content） */
    private String keyword;

    /** 返回条数 */
    @Builder.Default
    private long limit = 10;

    /** 可选过滤：文件结果 ID */
    private Long documentId;

    /** 可选过滤：所有者 */
    private String owner;

    /** 可选过滤：切片内容类型（与向量通道同口径透传） */
    private String contentType;
}
