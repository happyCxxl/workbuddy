package com.workbuddy.model;

import com.workbuddy.common.domain.embed.EmbeddingRequest;
import com.workbuddy.common.domain.embed.EmbeddingResult;
import com.workbuddy.common.domain.model.catalog.ModelDescriptor;
import com.workbuddy.common.domain.model.chat.ChatRequest;
import com.workbuddy.common.domain.model.chat.ChatResult;
import com.workbuddy.common.domain.model.vision.VisionRequest;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.WorkbuddyException;

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

    /**
     * 视觉调用：默认不支持，由具备视觉能力的适配器覆盖。
     *
     * @param model   目录行
     * @param request 视觉请求
     * @return 视觉结果（与对话共用结果结构）
     */
    default ChatResult vision(ModelDescriptor model, VisionRequest request) {
        throw new WorkbuddyException(ErrorCode.MODEL_MODALITY_UNSUPPORTED,
                "模型未支持视觉能力：" + model.getModelKey());
    }
}
