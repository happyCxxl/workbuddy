package com.workbuddy.model.invoke.pipeline;

/**
 * 阶段链：按装配顺序推进阶段。
 */
public interface StageChain {

    /**
     * 推进到下一个阶段。
     *
     * @param context 调用上下文
     */
    void proceed(CallContext context);
}
