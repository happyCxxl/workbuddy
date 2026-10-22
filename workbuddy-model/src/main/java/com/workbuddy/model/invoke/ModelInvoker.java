package com.workbuddy.model.invoke;

import com.workbuddy.model.invoke.pipeline.AttemptStage;
import com.workbuddy.model.invoke.pipeline.CallContext;
import com.workbuddy.model.invoke.pipeline.CallStage;
import com.workbuddy.model.invoke.pipeline.CandidateStage;
import com.workbuddy.model.invoke.pipeline.RequestStage;
import com.workbuddy.model.invoke.pipeline.RouteStage;
import com.workbuddy.model.invoke.pipeline.StageChainImpl;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 调用管线执行器：按固定顺序跑「请求 → 候选 → 路由 → 尝试」，返回上下文里的结果。
 *
 * <p>阶段顺序在装配处固定，不引入插件注册表；新增横切关注点 = 新增一个阶段并插入顺序。
 */
@Component
public class ModelInvoker {

    private final List<CallStage> stages;

    /**
     * 装配阶段链。
     *
     * @param requestStage   请求阶段
     * @param candidateStage 候选阶段
     * @param routeStage     路由阶段
     * @param attemptStage   尝试阶段
     */
    public ModelInvoker(RequestStage requestStage, CandidateStage candidateStage,
                        RouteStage routeStage, AttemptStage attemptStage) {
        this.stages = List.of(requestStage, candidateStage, routeStage, attemptStage);
    }

    /**
     * 执行一次调用。
     *
     * @param context 调用上下文（调用回调必填）
     * @param <T>     结果类型
     * @return 调用结果
     */
    @SuppressWarnings("unchecked")
    public <T> T invoke(CallContext context) {
        new StageChainImpl(stages).proceed(context);
        return (T) context.getResult();
    }
}
