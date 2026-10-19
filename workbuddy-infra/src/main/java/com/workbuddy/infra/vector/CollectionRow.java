package com.workbuddy.infra.vector;

import lombok.Data;

import java.util.List;

/**
 * 向量库行：与集合 schema 字段一一对应（id/document_id/owner/content_type/parent_chunk_id/
 * content/title_path/source_element_ids/vector）。
 */
@Data
public class CollectionRow {

    /** 主键（chunkId） */
    private String id;

    /** 文档 ID（文件结果，业务过滤用） */
    private Long documentId;

    /** 归属（业务过滤用） */
    private String owner;

    /** 内容类型（ChunkContentType 枚举名） */
    private String contentType;

    /** 父片 ID（父子检索扩展预留） */
    private String parentChunkId;

    /** 正文（BM25 全文通道） */
    private String content;

    /** 标题路径（BM25 全文通道） */
    private String titlePath;

    /** 来源元素 ID（溯源） */
    private String sourceElementIds;

    /** 向量本体 */
    private List<Float> vector;
}
