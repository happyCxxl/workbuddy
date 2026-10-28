package com.workbuddy.common.enums.user;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用户状态码单测。
 *
 * <p>守护 {@code of}/{@code isEnabled} 的码值映射：1=启用、0=停用，未知名返回 null；
 * 映射写反会把停用用户放进来。
 */
class UserStatusTest {

    /**
     * 码值映射与空值兜底。
     *
     * <p>守护 {@code of}：空值或未知名必须返回 null，不能默认成启用。
     */
    @Test
    void ofShouldMapCodeAndReturnNullForUnknown() {
        assertEquals(UserStatus.ENABLED, UserStatus.of(1));
        assertEquals(UserStatus.DISABLED, UserStatus.of(0));
        assertNull(UserStatus.of(null));
        assertNull(UserStatus.of(9));
    }

    /**
     * 启用判定只认 1。
     *
     * <p>守护 {@code isEnabled}：0、null、未知名都必须为 false。
     */
    @Test
    void isEnabledShouldOnlyAcceptEnabledCode() {
        assertTrue(UserStatus.isEnabled(1));
        assertFalse(UserStatus.isEnabled(0));
        assertFalse(UserStatus.isEnabled(null));
        assertFalse(UserStatus.isEnabled(9));
    }
}
