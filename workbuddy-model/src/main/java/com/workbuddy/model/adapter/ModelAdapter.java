package com.workbuddy.model.adapter;

import com.workbuddy.common.domain.embed.EmbeddingRequest;
import com.workbuddy.common.domain.embed.EmbeddingResult;
import com.workbuddy.common.domain.model.catalog.ModelDescriptor;
import com.workbuddy.common.domain.model.chat.ChatRequest;
import com.workbuddy.common.domain.model.chat.ChatResult;

/**
 * 协议适配器：我们的契约 ↔ 厂商协议；上层只认本接口。
 */
public interface ModelAdapter {

    /**
     * 对话调用。
     *
     * @param model   目录行（厂商 + 模型名）
     * @param request 对话请求
     * @return 对话结果
     */
    ChatResult chat(ModelDescriptor model, ChatRequest request);

    /**
     * 向量编码调用。
     *
     * @param model   目录行（厂商 + 模型名）
     * @param request 向量化请求
     * @return 向量结果
     */
    EmbeddingResult embed(ModelDescriptor model, EmbeddingRequest request);
}
