package com.workbuddy.common.enums.strategy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 策略类型单测。
 *
 * <p>守护 {@code of} 的键解析：策略类型是版本表的 type 维度，键错位即保存校验失真。
 */
class StrategyTypeTest {

    /**
     * 每个策略类型都能按键解析回来，且键非空。
     *
     * <p>守护 {@code of} 与 {@code key}：键改错即失败（版本表按类型查不到）。
     */
    @Test
    void ofShouldResolveEveryKey() {
        for (StrategyType type : StrategyType.values()) {
            assertEquals(type, StrategyType.of(type.key()), () -> type + " 按键解析失败");
            assertFalse(type.key().isBlank(), () -> type + " 键为空");
        }
        assertNull(StrategyType.of("no-such-type"));
    }
}
