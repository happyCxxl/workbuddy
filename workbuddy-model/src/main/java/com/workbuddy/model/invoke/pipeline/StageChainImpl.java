package com.workbuddy.model.invoke.pipeline;

import java.util.List;

/**
 * 阶段链实现：持有固定顺序的阶段列表与游标，一次调用只推进一遍。
 */
public final class StageChainImpl implements StageChain {

    private final List<CallStage> stages;

    private int index;

    /**
     * 构造阶段链。
     *
     * @param stages 有序阶段
     */
    public StageChainImpl(List<CallStage> stages) {
        this.stages = List.copyOf(stages);
    }

    /**
     * 推进到下一个阶段；链尾直接返回。
     *
     * @param context 调用上下文
     */
    @Override
    public void proceed(CallContext context) {
        if (index >= stages.size()) {
            return;
        }
        stages.get(index++).apply(context, this);
    }
}
