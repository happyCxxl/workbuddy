package com.workbuddy.common.dto.response.retrieval;

import lombok.Data;

/**
 * 检索命中：命中字段原样入 VO（命中直取不回查库）；
 * 父片展开命中 = 父片内容 + isParent=true。
 */
@Data
public class SearchHitVO {

    /** 片 ID（父片展开后为父片 ID） */
    private String chunkId;

    /** 片文本 */
    private String content;

    /** 标题路径 */
    private String titlePath;

    /** 源元素 ID（JSON 串） */
    private String sourceElementIds;

    /** 文件结果 ID（雪花 ID，序列化为字符串下发） */
    private Long documentId;

    /** 片类型 */
    private String contentType;

    /** 父片 ID */
    private String parentChunkId;

    /** 分数（向量=相似度；HYBRID=RRF 融合分；纯全文=null） */
    private Double score;

    /** 是否父片展开（命中子片但返回父片全文） */
    private Boolean isParent;
}
