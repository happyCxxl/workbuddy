package com.workbuddy.common.domain.embed;

import lombok.Data;

import java.util.List;

/**
 * 向量化请求（ModelGatewayPort.embed 入参）：一批输入文本 + 模型 + 调用口径。
 * worker 网关适配器按此组装 RemoteEmbeddingService 调用。
 *
 * @author cxxl
 */
@Data
public class EmbeddingRequest {

    /** 模型名（网关 llm_channel.model 口径） */
    private String model;

    /** 本批输入文本（原样编码，顺序即请求顺序） */
    private List<String> texts;

    /** 单批超时（毫秒，显式指定；网关支持 1s~15min） */
    private Integer timeoutMs;

    /** 期望批大小（网关默认 20；向量化侧按策略 batchSize 拆批） */
    private Integer batchSize;

    /** 本批 requestId（成本审计/追踪） */
    private String requestId;
}
