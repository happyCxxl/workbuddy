package com.workbuddy.model.service.support.route;

import com.workbuddy.model.ModelRef;
import com.workbuddy.model.config.ModelRouteProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 熔断器单测：失败率达到阈值后打开，成功不打开。
 */
class ModelCircuitBreakersTest {

    private static final ModelRef REF = new ModelRef("dashscope", "qwen-max");

    private ModelCircuitBreakers breakers;

    @BeforeEach
    void setUp() {
        ModelRouteProperties properties = new ModelRouteProperties();
        properties.setErrorTriggerRatio(0.5);
        properties.setErrorWindowSeconds(60);
        properties.setCooldownSeconds(60);
        breakers = new ModelCircuitBreakers(properties);
    }

    /** 连续失败达到最少调用数与失败率后打开（{@code shouldOpenAfterFailures}）。 */
    @Test
    void shouldOpenAfterFailures() {
        for (int i = 0; i < 10; i++) {
            assertTrue(breakers.tryAcquire(REF));
            breakers.onFailure(REF, 10, new RuntimeException("boom"));
        }

        assertTrue(breakers.isOpen(REF));
    }

    /** 成功不打开（{@code shouldStayClosedOnSuccess}）。 */
    @Test
    void shouldStayClosedOnSuccess() {
        for (int i = 0; i < 20; i++) {
            assertTrue(breakers.tryAcquire(REF));
            breakers.onSuccess(REF, 10);
        }

        assertFalse(breakers.isOpen(REF));
    }

    /** 归还许可不算失败（{@code releaseShouldNotTrip}）。 */
    @Test
    void releaseShouldNotTrip() {
        for (int i = 0; i < 20; i++) {
            assertTrue(breakers.tryAcquire(REF));
            breakers.releasePermission(REF);
        }

        assertFalse(breakers.isOpen(REF));
    }
}
