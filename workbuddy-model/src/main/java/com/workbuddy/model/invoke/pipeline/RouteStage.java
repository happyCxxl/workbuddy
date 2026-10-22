package com.workbuddy.model.invoke.pipeline;

import org.springframework.stereotype.Component;

/**
 * 路由阶段：候选顺序由 {@link com.workbuddy.model.route.ModelRouter} 给出，本阶段留作后续打分/冷却扩展点。
 */
@Component
public class RouteStage implements CallStage {

    /**
     * 直接推进（M1 无额外排序）。
     *
     * @param context 调用上下文
     * @param next    后续阶段链
     */
    @Override
    public void apply(CallContext context, StageChain next) {
        next.proceed(context);
    }
}
