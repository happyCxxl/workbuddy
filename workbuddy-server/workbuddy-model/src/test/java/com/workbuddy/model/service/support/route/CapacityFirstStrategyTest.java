package com.workbuddy.model.service.support.route;

import com.workbuddy.common.domain.model.catalog.ModelDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 容量优先策略单测：容量降序，容量并列按延迟升序。
 */
class CapacityFirstStrategyTest {

    private final CapacityFirstStrategy strategy = new CapacityFirstStrategy();

    private RouteCandidate candidate(String modelKey, double capacity, long latency) {
        ModelDescriptor model = new ModelDescriptor();
        model.setVendorCode("dashscope");
        model.setModelKey(modelKey);
        return new RouteCandidate(model, capacity, latency);
    }

    /** 容量高者优先，并列时延迟低者优先（{@code shouldOrderByCapacityThenLatency}）。 */
    @Test
    void shouldOrderByCapacityThenLatency() {
        List<RouteCandidate> ordered = strategy.order(List.of(
                candidate("low", 0.2, 1),
                candidate("slow", 0.9, 50),
                candidate("fast", 0.9, 5)));

        assertEquals(List.of("fast", "slow", "low"),
                ordered.stream().map(candidate -> candidate.model().getModelKey()).toList());
    }
}
