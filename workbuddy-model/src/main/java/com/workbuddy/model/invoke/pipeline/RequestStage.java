package com.workbuddy.model.invoke.pipeline;

import com.workbuddy.common.enums.model.ModelCapability;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.utils.NullUtil;
import cn.hutool.core.util.StrUtil;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 请求阶段：补齐追踪 ID、能力缺省与执行回调校验。
 */
@Component
public class RequestStage implements CallStage {

    /**
     * 补齐请求上下文后推进。
     *
     * @param context 调用上下文
     * @param next    后续阶段链
     */
    @Override
    public void apply(CallContext context, StageChain next) {
        if (StrUtil.isBlank(context.getRequestId())) {
            context.setRequestId(UUID.randomUUID().toString());
        }
        if (NullUtil.isNull(context.getCapability())) {
            context.setCapability(ModelCapability.CHAT);
        }
        if (NullUtil.isNull(context.getCall())) {
            throw new KnowledgeException(ErrorCode.MODEL_BAD_REQUEST, "调用回调为空");
        }
        next.proceed(context);
    }
}
