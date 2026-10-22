package com.workbuddy.model.route;

import com.workbuddy.common.domain.model.catalog.ModelDescriptor;

import java.util.List;

/**
 * 路由策略：把候选排成尝试顺序；M1 只做直通，后续接容量/延迟打分。
 */
public interface RoutingStrategy {

    /**
     * 对候选排序。
     *
     * @param candidates 已过硬闸门的候选
     * @return 有序候选
     */
    List<ModelDescriptor> order(List<ModelDescriptor> candidates);
}
