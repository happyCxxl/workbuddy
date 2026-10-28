package com.workbuddy.common.enums.knowledge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 策略绑定开关单测。
 *
 * <p>守护 {@code isOn}/{@code isOff} 的存量兼容口径：null 与非 0 均视为开启，
 * 只有显式 0 是关闭——存量行的空值不得被误判成关闭。
 */
class StrategyBindingSwitchTest {

    /**
     * null 视为开启（存量行兼容），非 0 开启，仅 0 关闭。
     *
     * <p>守护 {@code isOn}：把 null 判成关闭即失败（存量库的策略绑定被误停）。
     */
    @Test
    void isOnShouldTreatNullAndNonZeroAsEnabled() {
        assertTrue(StrategyBindingSwitch.isOn(null));
        assertTrue(StrategyBindingSwitch.isOn(1));
        assertFalse(StrategyBindingSwitch.isOn(0));
    }

    /**
     * isOff 与 isOn 互为反面。
     *
     * <p>守护 {@code isOff}：与 isOn 口径不一致即失败。
     */
    @Test
    void isOffShouldMirrorIsOn() {
        assertFalse(StrategyBindingSwitch.isOff(null));
        assertTrue(StrategyBindingSwitch.isOff(0));
        assertFalse(StrategyBindingSwitch.isOff(1));
    }
}
