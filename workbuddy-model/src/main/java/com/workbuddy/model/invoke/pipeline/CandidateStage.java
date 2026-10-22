package com.workbuddy.model.invoke.pipeline;

import com.workbuddy.model.route.ModelRouter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 候选阶段：按能力与模型名过滤出有序候选。
 */
@Component
@RequiredArgsConstructor
public class CandidateStage implements CallStage {

    private final ModelRouter modelRouter;

    /**
     * 填充候选后推进。
     *
     * @param context 调用上下文
     * @param next    后续阶段链
     */
    @Override
    public void apply(CallContext context, StageChain next) {
        context.setCandidates(modelRouter.candidates(context.getCapability(), context.getModelKeyFilter()));
        next.proceed(context);
    }
}
