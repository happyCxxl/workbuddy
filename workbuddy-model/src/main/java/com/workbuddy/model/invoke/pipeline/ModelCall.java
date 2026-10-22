package com.workbuddy.model.invoke.pipeline;

import com.workbuddy.common.domain.model.catalog.ModelDescriptor;

/**
 * 单候选执行回调：门面把具体调用（chat / embed / vision）包进来。
 *
 * @param <T> 结果类型
 */
@FunctionalInterface
public interface ModelCall<T> {

    /**
     * 调用一个候选模型。
     *
     * @param model 目录行
     * @return 调用结果
     */
    T invoke(ModelDescriptor model);
}
