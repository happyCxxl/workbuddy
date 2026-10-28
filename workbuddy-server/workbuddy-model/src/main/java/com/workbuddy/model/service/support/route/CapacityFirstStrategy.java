package com.workbuddy.model.service.support.route;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * 容量优先策略：容量分降序，容量并列时延迟升序。
 *
 * <p>容量已按 slow start 系数折减，恢复期模型自然排在后面。
 */
@Component
public class CapacityFirstStrategy implements RoutingStrategy {

    /**
     * 按容量与延迟排序。
     *
     * @param candidates 候选
     * @return 有序候选
     */
    @Override
    public List<RouteCandidate> order(List<RouteCandidate> candidates) {
        return candidates.stream()
                .sorted(Comparator.comparingDouble(RouteCandidate::capacity).reversed()
                        .thenComparingLong(RouteCandidate::latencyMillis))
                .toList();
    }
}
