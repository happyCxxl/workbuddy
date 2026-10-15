package com.workbuddy.worker.indexing.search;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 向量检索查询。
 *
 * @author cxxl
 */
@Data
@Builder
public class VectorQuery {

    /** 查询向量（维度与 collection 一致） */
    private List<Float> vector;

    /** 返回条数 */
    @Builder.Default
    private int topK = 10;

    /** 可选过滤：文件结果 ID */
    private Long documentId;

    /** 可选过滤：所有者 */
    private String owner;

    /** 可选过滤：片类型 */
    private String contentType;
}
