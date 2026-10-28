package com.workbuddy.model.service.support.route;

import java.util.List;

/**
 * 路由策略：把候选排成尝试顺序；M2 默认容量优先。
 */
public interface RoutingStrategy {

    /**
     * 对候选排序。
     *
     * @param candidates 已过硬闸门、带容量与延迟的候选
     * @return 有序候选
     */
    List<RouteCandidate> order(List<RouteCandidate> candidates);
}
