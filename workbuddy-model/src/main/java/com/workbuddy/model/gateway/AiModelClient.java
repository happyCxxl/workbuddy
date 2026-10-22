package com.workbuddy.model.gateway;

import com.workbuddy.common.domain.embed.EmbeddingRequest;
import com.workbuddy.common.domain.embed.EmbeddingResult;
import com.workbuddy.common.domain.model.chat.ChatRequest;
import com.workbuddy.common.domain.model.chat.ChatResult;
import com.workbuddy.common.enums.model.ModelCapability;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.model.adapter.ModelAdapter;
import com.workbuddy.model.invoke.ModelInvoker;
import com.workbuddy.model.invoke.pipeline.CallContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 模型门面实现：把请求包成调用上下文，交给管线执行；自身不写选路细节。
 */
@Component
@RequiredArgsConstructor
public class AiModelClient implements AiClient {

    private final ModelAdapter modelAdapter;

    private final ModelInvoker modelInvoker;

    /**
     * 对话：按所需能力选模型。
     *
     * @param request 对话请求
     * @return 对话结果
     */
    @Override
    public ChatResult chat(ChatRequest request) {
        ThrowUtil.throwIf(NullUtil.isNull(request),ErrorCode.MODEL_BAD_REQUEST, "对话请求为空");
        CallContext context = new CallContext();
        context.setCapability(NullUtil.isNull(request.getCapability())
                ? ModelCapability.CHAT : request.getCapability());
        context.setCall(model -> modelAdapter.chat(model, request));
        return modelInvoker.invoke(context);
    }

    /**
     * 向量编码：请求里的模型名作为精确过滤条件。
     *
     * @param request 向量化请求
     * @return 向量结果
     */
    @Override
    public EmbeddingResult embed(EmbeddingRequest request) {
        ThrowUtil.throwIf(NullUtil.isNull(request),ErrorCode.MODEL_BAD_REQUEST, "向量化请求为空");
        CallContext context = new CallContext();
        context.setCapability(ModelCapability.EMBEDDING);
        context.setModelKeyFilter(request.getModel());
        context.setCall(model -> modelAdapter.embed(model, request));
        return modelInvoker.invoke(context);
    }
}
