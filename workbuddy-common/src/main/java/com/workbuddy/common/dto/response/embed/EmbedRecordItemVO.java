package com.workbuddy.common.dto.response.embed;

import lombok.Data;

/**
 * 向量记录行（详情抽屉列表）：元数据与状态，不含向量本体（本体不下发前端）。
 *
 * @author cxxl
 */
@Data
public class EmbedRecordItemVO {

    /** 记录 ID */
    private String embeddingId;

    /** 切片 ID */
    private String chunkId;

    /** 切片内容类型 */
    private String contentType;

    /** 编码输入文本（列表展示做摘要截断） */
    private String inputText;

    /** Token 估算 */
    private Integer tokenCount;

    /** 网关 requestId */
    private String requestId;

    /** 状态（SUCCESS/CACHED/SKIPPED/FAILED） */
    private String status;

    /** 是否复用命中 */
    private Boolean cacheHit;
}
