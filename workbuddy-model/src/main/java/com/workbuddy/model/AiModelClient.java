package com.workbuddy.model;

import com.workbuddy.common.domain.embed.EmbeddingRequest;
import com.workbuddy.common.domain.embed.EmbeddingResult;
import com.workbuddy.common.domain.model.chat.ChatRequest;
import com.workbuddy.common.domain.model.chat.ChatResult;
import com.workbuddy.common.domain.model.vision.VisionRequest;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.model.service.support.invoke.ModelInvoker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 模型门面实现：只做入参校验与转发，选路、限流与降级都在 {@link ModelInvoker}。
 */
@Component
@RequiredArgsConstructor
public class AiModelClient implements AiClient {

    private final ModelInvoker modelInvoker;

    /**
     * 对话：转发给调用器。
     *
     * @param request 对话请求
     * @return 对话结果
     */
    @Override
    public ChatResult chat(ChatRequest request) {
        ThrowUtil.throwIf(NullUtil.isNull(request), ErrorCode.MODEL_BAD_REQUEST, "对话请求为空");
        return modelInvoker.chat(request);
    }

    /**
     * 向量编码：转发给调用器。
     *
     * @param request 向量化请求
     * @return 向量结果
     */
    @Override
    public EmbeddingResult embed(EmbeddingRequest request) {
        ThrowUtil.throwIf(NullUtil.isNull(request), ErrorCode.MODEL_BAD_REQUEST, "向量化请求为空");
        return modelInvoker.embed(request);
    }

    /**
     * 视觉/OCR：转发给调用器。
     *
     * @param request 视觉请求
     * @return 视觉结果
     */
    @Override
    public ChatResult vision(VisionRequest request) {
        ThrowUtil.throwIf(NullUtil.isNull(request), ErrorCode.MODEL_BAD_REQUEST, "视觉请求为空");
        return modelInvoker.vision(request);
    }
}
