package com.workbuddy.model.config;

import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.startup.StartupCheckChain;
import com.workbuddy.model.startup.ModelRouteValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路由参数单测：默认值与配置表逐项对齐，缺省即启动；越界取值在启动期校验处被拒。
 *
 * @author cxxl
 */
class ModelRoutePropertiesTest {

    /** 链的替身：不推进，用于断言失败路径不把控制权交给下一项 */
    private static final StartupCheckChain NO_PROCEED = () -> {
    };

    @Test
    void defaultsShouldMatchDesignTable() {
        ModelRouteProperties properties = new ModelRouteProperties();

        assertEquals(1.0D, properties.getBetaCapacity());
        assertEquals(0.5D, properties.getBetaLatency());
        assertEquals(300, properties.getLatencyWindowSeconds());
        assertEquals(0.8D, properties.getCapacityTriggerRatio());
        assertEquals(0.05D, properties.getErrorTriggerRatio());
        assertEquals(60, properties.getErrorWindowSeconds());
        assertEquals(60, properties.getCooldownSeconds());
        assertEquals(45, properties.getSlowStartSeconds());
    }

    @Test
    void millisViewsShouldConvertSeconds() {
        ModelRouteProperties properties = new ModelRouteProperties();

        assertEquals(300_000L, properties.latencyWindowMillis());
        assertEquals(60_000L, properties.errorWindowMillis());
    }

    /**
     * 无消费方的配置键不再留：`threshold-window-seconds` 及其毫秒视图都没有读写入口，
     * 该键的作用已由熔断器的时间窗承担。
     */
    @Test
    void thresholdWindowKeyShouldNotExist() {
        assertThrows(NoSuchMethodException.class,
                () -> ModelRouteProperties.class.getMethod("getThresholdWindowSeconds"));
        assertThrows(NoSuchMethodException.class,
                () -> ModelRouteProperties.class.getMethod("thresholdWindowMillis"));
    }

    /**
     * 配置面只有秒级键：打开保持与爬升时长没有毫秒读写入口，时间行为的用例自建熔断参数。
     */
    @Test
    void millisScaleFieldsShouldNotExist() {
        assertThrows(NoSuchMethodException.class, () -> ModelRouteProperties.class.getMethod("getCooldownMillis"));
        assertThrows(NoSuchMethodException.class, () -> ModelRouteProperties.class.getMethod("getSlowStartMillis"));
        assertThrows(NoSuchMethodException.class,
                () -> ModelRouteProperties.class.getMethod("setCooldownMillis", long.class));
        assertThrows(NoSuchMethodException.class,
                () -> ModelRouteProperties.class.getMethod("setSlowStartMillis", long.class));
    }

    @Test
    void allDefaultsShouldPassValidation() {
        assertDoesNotThrow(() -> new ModelRouteProperties().validate());
    }

    @Test
    void zeroWeightShouldBeAccepted() {
        ModelRouteProperties properties = new ModelRouteProperties();
        properties.setBetaCapacity(0D);
        properties.setBetaLatency(0D);

        assertDoesNotThrow(properties::validate);
    }

    @Test
    void negativeWeightShouldBeRejected() {
        ModelRouteProperties properties = new ModelRouteProperties();
        properties.setBetaCapacity(-0.1D);

        KnowledgeException error = assertThrows(KnowledgeException.class, properties::validate);

        assertEquals(ErrorCode.PARAM_INVALID, error.getErrorCode());
        assertTrue(error.getMessage().contains("knowledge.model.route.beta-capacity"));
    }

    @Test
    void ratioOutOfRangeShouldBeRejected() {
        ModelRouteProperties zeroRatio = new ModelRouteProperties();
        zeroRatio.setErrorTriggerRatio(0D);
        ModelRouteProperties overRatio = new ModelRouteProperties();
        overRatio.setCapacityTriggerRatio(1.5D);

        KnowledgeException zeroError = assertThrows(KnowledgeException.class, zeroRatio::validate);
        KnowledgeException overError = assertThrows(KnowledgeException.class, overRatio::validate);

        assertTrue(zeroError.getMessage().contains("knowledge.model.route.error-trigger-ratio"));
        assertTrue(overError.getMessage().contains("knowledge.model.route.capacity-trigger-ratio"));
    }

    @Test
    void ratioAtBoundaryShouldBeAccepted() {
        ModelRouteProperties properties = new ModelRouteProperties();
        properties.setCapacityTriggerRatio(1D);
        properties.setErrorTriggerRatio(1D);

        assertDoesNotThrow(properties::validate);
    }

    @Test
    void negativeDurationShouldBeRejected() {
        ModelRouteProperties properties = new ModelRouteProperties();
        properties.setSlowStartSeconds(-1);

        KnowledgeException error = assertThrows(KnowledgeException.class, properties::validate);

        assertTrue(error.getMessage().contains("knowledge.model.route.slow-start-seconds"));
    }

    @Test
    void zeroDurationShouldBeAccepted() {
        ModelRouteProperties properties = new ModelRouteProperties();
        properties.setCooldownSeconds(0);
        properties.setSlowStartSeconds(0);

        assertDoesNotThrow(properties::validate);
        assertEquals(0, properties.getCooldownSeconds());
        assertEquals(0, properties.getSlowStartSeconds());
    }

    @Test
    void validatorShouldRejectSameAsProperties() {
        ModelRouteProperties properties = new ModelRouteProperties();
        properties.setLatencyWindowSeconds(-5);

        assertThrows(KnowledgeException.class, () -> new ModelRouteValidator(properties).check(NO_PROCEED));
    }
}
