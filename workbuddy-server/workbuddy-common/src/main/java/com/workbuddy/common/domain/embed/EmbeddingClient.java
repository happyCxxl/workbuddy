package com.workbuddy.common.domain.embed;

/**
 * 向量化出口：Knowledge 只声明批次入参与结果，具体供应商由实现侧决定。
 */
public interface EmbeddingClient {

    /**
     * 一批文本编码为向量。
     *
     * @param request 向量化请求（模型、批次、维度）
     * @return 与输入同序的向量结果
     */
    EmbeddingResult embed(EmbeddingRequest request);
}
