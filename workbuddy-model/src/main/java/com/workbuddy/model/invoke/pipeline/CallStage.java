package com.workbuddy.model.invoke.pipeline;

/**
 * 调用管线阶段：环绕语义，处理完前置逻辑后调用 {@code next.proceed()}。
 */
public interface CallStage {

    /**
     * 执行本阶段。
     *
     * @param context 调用上下文
     * @param next    后续阶段链
     */
    void apply(CallContext context, StageChain next);
}
