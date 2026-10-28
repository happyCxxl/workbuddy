package com.workbuddy.auth.startup;

import com.workbuddy.common.exception.WorkbuddyException;
import com.workbuddy.common.startup.StartupCheckChain;
import com.workbuddy.auth.config.JwtProperties;
import com.workbuddy.auth.config.LoginGuardProperties;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 认证配置校验单测：密钥为空、旧版占位串、短于 32 字符、有效期非正、防护阈值为非正即拒；防护关闭时跳过阈值校验。
 */
class AuthConfigCheckTest {

    private static final String SECRET = "test-secret-0123456789-abcdefghijklmnopqrstuvwxyz";

    /** 链的替身：记录是否被推进 */
    private final AtomicBoolean proceeded = new AtomicBoolean();

    private final StartupCheckChain next = () -> proceeded.set(true);

    /**
     * 守护 {@code shouldPassWithValidConfig}：该场景行为改动即失败。
     */
    @Test
    void shouldPassWithValidConfig() {
        new AuthConfigCheck(jwt(SECRET, 1, 30), guard()).check(next);

        assertTrue(proceeded.get(), "通过路径必须推进链");
    }

    /**
     * 守护 {@code shouldRejectBlankSecret}：该场景行为改动即失败。
     */
    @Test
    void shouldRejectBlankSecret() {
        WorkbuddyException error = assertThrows(WorkbuddyException.class,
                () -> new AuthConfigCheck(jwt("  ", 1, 30), guard()).check(next));

        assertTrue(error.getMessage().contains("secret"), () -> "message=" + error.getMessage());
        assertFalse(proceeded.get(), "失败即止，不推进链");
    }

    /**
     * 守护 {@code shouldRejectNonPositiveTtl}：该场景行为改动即失败。
     */
    @Test
    void shouldRejectNonPositiveTtl() {
        assertThrows(WorkbuddyException.class,
                () -> new AuthConfigCheck(jwt(SECRET, 0, 30), guard()).check(next));
        assertThrows(WorkbuddyException.class,
                () -> new AuthConfigCheck(jwt(SECRET, 1, -1), guard()).check(next));
    }

    /**
     * 守护 {@code shouldRejectNonPositiveGuardThreshold}：该场景行为改动即失败。
     */
    @Test
    void shouldRejectNonPositiveGuardThreshold() {
        LoginGuardProperties guard = guard();
        guard.setFailThreshold(0);

        assertThrows(WorkbuddyException.class, () -> new AuthConfigCheck(jwt(SECRET, 1, 30), guard).check(next));
    }

    /** 防护关闭时阈值不参与任何判断，非正也不拒（{@code shouldSkipGuardCheckWhenDisabled}）。 */
    @Test
    void shouldSkipGuardCheckWhenDisabled() {
        LoginGuardProperties guard = guard();
        guard.setEnabled(false);
        guard.setFailThreshold(0);
        guard.setIpFailThreshold(0);
        guard.setLockMinutes(0);

        assertDoesNotThrow(() -> new AuthConfigCheck(jwt(SECRET, 1, 30), guard).check(next));
        assertTrue(proceeded.get());
    }

    /**
     * 守护 {@code shouldRejectShortSecret}：短密钥签出的令牌防护力不足，一律在启动期拒绝。
     */
    @Test
    void shouldRejectShortSecret() {
        WorkbuddyException error = assertThrows(WorkbuddyException.class,
                () -> new AuthConfigCheck(jwt("short", 1, 30), guard()).check(next));

        assertTrue(error.getMessage().contains("secret"), () -> "message=" + error.getMessage());
        assertFalse(proceeded.get(), "失败即止，不推进链");
    }

    /**
     * 守护 {@code shouldRejectLegacyPlaceholderSecret}：旧版占位串出现在任何配置源都拒绝启动。
     */
    @Test
    void shouldRejectLegacyPlaceholderSecret() {
        WorkbuddyException error = assertThrows(WorkbuddyException.class,
                () -> new AuthConfigCheck(
                        jwt("workbuddy-jwt-secret-change-me-in-production", 1, 30), guard()).check(next));

        assertTrue(error.getMessage().contains("占位"), () -> "message=" + error.getMessage());
        assertFalse(proceeded.get(), "失败即止，不推进链");
    }

    /** 防护配置：启用且阈值合法（阈值由用例显式给出，不依赖配置类的字段初值） */
    private LoginGuardProperties guard() {
        LoginGuardProperties properties = new LoginGuardProperties();
        properties.setEnabled(true);
        properties.setFailThreshold(5);
        properties.setIpFailThreshold(20);
        properties.setLockMinutes(15);
        return properties;
    }

    private JwtProperties jwt(String secret, long ttlDays, long rememberTtlDays) {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(secret);
        properties.setTtlDays(ttlDays);
        properties.setTtlDaysRemember(rememberTtlDays);
        return properties;
    }
}
