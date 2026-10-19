package com.workbuddy.auth.service.support;

import com.workbuddy.auth.config.LoginGuardProperties;
import com.workbuddy.infra.redis.RedisCounter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 登录失败防护：两维度阈值判定、计数写入与成功清零。
 */
@ExtendWith(MockitoExtension.class)
class LoginAttemptGuardTest {

    private static final String ACCOUNT_KEY = "knowledge:auth:fail:account:alice:10.0.0.1";

    private static final String IP_KEY = "knowledge:auth:fail:ip:10.0.0.1";

    @Mock
    private RedisCounter redisCounter;

    private LoginGuardProperties properties;

    private LoginAttemptGuard guard;

    /**
     * 每个用例重建配置与防护器，避免用例间共享计数状态。
     */
    @BeforeEach
    void setUp() {
        properties = new LoginGuardProperties();
        guard = new LoginAttemptGuard(redisCounter, properties);
    }

    /**
     * 防护开关关闭时恒不锁定，且不查询 Redis。
     */
    @Test
    void isLockedReturnsFalseWhenGuardDisabled() {
        properties.setEnabled(false);

        assertFalse(guard.isLocked("alice", "10.0.0.1"));
        verifyNoInteractions(redisCounter);
    }

    /**
     * 账号维度计数达到阈值即锁定（账号先命中，IP 维度短路不再查询）。
     */
    @Test
    void isLockedReturnsTrueWhenAccountReachesThreshold() {
        when(redisCounter.get(ACCOUNT_KEY)).thenReturn((long) properties.getFailThreshold());

        assertTrue(guard.isLocked("alice", "10.0.0.1"));
    }

    /**
     * 账号维度差一次未达阈值时不锁定（阈值边界）。
     */
    @Test
    void isLockedReturnsFalseWhenAccountBelowThreshold() {
        when(redisCounter.get(ACCOUNT_KEY)).thenReturn(properties.getFailThreshold() - 1L);
        when(redisCounter.get(IP_KEY)).thenReturn(0L);

        assertFalse(guard.isLocked("alice", "10.0.0.1"));
    }

    /**
     * IP 维度跨账号累计达到阈值即锁定。
     */
    @Test
    void isLockedReturnsTrueWhenIpReachesThreshold() {
        when(redisCounter.get(ACCOUNT_KEY)).thenReturn(0L);
        when(redisCounter.get(IP_KEY)).thenReturn((long) properties.getIpFailThreshold());

        assertTrue(guard.isLocked("alice", "10.0.0.1"));
    }

    /**
     * 记一次失败：账号与 IP 两个维度都按锁定窗口计数。
     */
    @Test
    void recordFailureCountsBothDimensions() {
        properties.setLockMinutes(15);

        guard.recordFailure("alice", "10.0.0.1");

        verify(redisCounter).increment(ACCOUNT_KEY, Duration.ofMinutes(15));
        verify(redisCounter).increment(IP_KEY, Duration.ofMinutes(15));
    }

    /**
     * 防护关闭时不计任何失败。
     */
    @Test
    void recordFailureDoesNothingWhenGuardDisabled() {
        properties.setEnabled(false);

        guard.recordFailure("alice", "10.0.0.1");

        verify(redisCounter, never()).increment(anyString(), any(Duration.class));
    }

    /**
     * 登录成功只清零账号维度，IP 维度保留（防止换账号继续撞库）。
     */
    @Test
    void clearAccountResetsAccountKeyOnly() {
        guard.clearAccount("alice", "10.0.0.1");

        verify(redisCounter).reset(ACCOUNT_KEY);
        verify(redisCounter, never()).reset(IP_KEY);
    }

    /**
     * 防护关闭时不清零。
     */
    @Test
    void clearAccountDoesNothingWhenGuardDisabled() {
        properties.setEnabled(false);

        guard.clearAccount("alice", "10.0.0.1");

        verify(redisCounter, never()).reset(anyString());
    }

    /**
     * 计数键格式固定：账号维度带账号与 IP，IP 维度只带 IP。
     */
    @Test
    void recordFailureUsesExpectedKeyFormat() {
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);

        guard.recordFailure("alice", "10.0.0.1");

        verify(redisCounter, times(2)).increment(keys.capture(), any(Duration.class));
        assertEquals(List.of(ACCOUNT_KEY, IP_KEY), keys.getAllValues());
    }
}
