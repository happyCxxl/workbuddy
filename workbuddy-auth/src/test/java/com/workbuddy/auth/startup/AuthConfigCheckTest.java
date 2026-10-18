package com.workbuddy.auth.startup;

import com.workbuddy.common.exception.KnowledgeException;
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
 * 认证配置校验单测：密钥为空、有效期非正、防护阈值为非正即拒；防护关闭时跳过阈值校验。
 */
class AuthConfigCheckTest {

    private static final String SECRET = "test-secret-0123456789-abcdefghijklmnopqrstuvwxyz";

    /** 链的替身：记录是否被推进 */
    private final AtomicBoolean proceeded = new AtomicBoolean();

    private final StartupCheckChain next = () -> proceeded.set(true);

    @Test
    void shouldPassWithValidConfig() {
        new AuthConfigCheck(jwt(SECRET, 1, 30), new LoginGuardProperties()).check(next);

        assertTrue(proceeded.get(), "通过路径必须推进链");
    }

    @Test
    void shouldRejectBlankSecret() {
        KnowledgeException error = assertThrows(KnowledgeException.class,
                () -> new AuthConfigCheck(jwt("  ", 1, 30), new LoginGuardProperties()).check(next));

        assertTrue(error.getMessage().contains("secret"), () -> "message=" + error.getMessage());
        assertFalse(proceeded.get(), "失败即止，不推进链");
    }

    @Test
    void shouldRejectNonPositiveTtl() {
        assertThrows(KnowledgeException.class,
                () -> new AuthConfigCheck(jwt(SECRET, 0, 30), new LoginGuardProperties()).check(next));
        assertThrows(KnowledgeException.class,
                () -> new AuthConfigCheck(jwt(SECRET, 1, -1), new LoginGuardProperties()).check(next));
    }

    @Test
    void shouldRejectNonPositiveGuardThreshold() {
        LoginGuardProperties guard = new LoginGuardProperties();
        guard.setFailThreshold(0);

        assertThrows(KnowledgeException.class, () -> new AuthConfigCheck(jwt(SECRET, 1, 30), guard).check(next));
    }

    /** 防护关闭时阈值不参与任何判断，非正也不拒 */
    @Test
    void shouldSkipGuardCheckWhenDisabled() {
        LoginGuardProperties guard = new LoginGuardProperties();
        guard.setEnabled(false);
        guard.setFailThreshold(0);
        guard.setIpFailThreshold(0);
        guard.setLockMinutes(0);

        assertDoesNotThrow(() -> new AuthConfigCheck(jwt(SECRET, 1, 30), guard).check(next));
        assertTrue(proceeded.get());
    }

    /** 短密钥只告警，不阻断启动（本地开发用短密钥） */
    @Test
    void shouldTolerateShortSecret() {
        assertDoesNotThrow(() -> new AuthConfigCheck(jwt("short", 1, 30), new LoginGuardProperties()).check(next));
        assertTrue(proceeded.get());
    }

    private JwtProperties jwt(String secret, long ttlDays, long rememberTtlDays) {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(secret);
        properties.setTtlDays(ttlDays);
        properties.setTtlDaysRemember(rememberTtlDays);
        return properties;
    }
}
