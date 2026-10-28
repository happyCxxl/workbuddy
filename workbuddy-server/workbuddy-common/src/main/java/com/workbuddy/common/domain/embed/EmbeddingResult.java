package com.workbuddy.common.domain.embed;

import lombok.Data;

import java.util.List;

/**
 * 向量化结果（ModelGatewayPort.embed 出参）：向量列表（与输入顺序一一对应）+ 维度 + 模型回显 + requestId。
 * 四关校验在管线侧消费本对象。
 */
@Data
public class EmbeddingResult {

    /** 向量列表（第 i 条对应第 i 段输入） */
    private List<List<Float>> embeddings;

    /** 维度（网关从上游响应带出） */
    private Integer dimension;

    /** 模型回显 */
    private String model;

    /** requestId（与请求一致） */
    private String requestId;
}
