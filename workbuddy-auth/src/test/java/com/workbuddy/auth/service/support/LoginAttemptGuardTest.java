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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 登录失败防护：两维度阈值判定、计数写入与成功清零。
 *
 * <p>守护 {@link LoginAttemptGuard#isLocked} 的阈值口径与 {@link LoginAttemptGuard#recordFailure}
 * 的双维度计数；失败类别按 {@code knowledge.auth.guard} 配置项区分。
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
     * 防护开关关闭时恒不锁定。
     *
     * <p>改坏 {@code isLocked} 的开关判断（去掉提前返回）即失败。
     */
    @Test
    void isLockedReturnsFalseWhenGuardDisabled() {
        properties.setEnabled(false);

        assertFalse(guard.isLocked("alice", "10.0.0.1"));
        verifyNoInteractions(redisCounter);
    }

    /**
     * 账号维度计数达到阈值即锁定。
     *
     * <p>守护 {@code isLocked} 的账号阈值：把 {@code >=} 改成 {@code >} 即失败。
     */
    @Test
    void isLockedReturnsTrueWhenAccountReachesThreshold() {
        when(redisCounter.get(ACCOUNT_KEY)).thenReturn((long) properties.getFailThreshold());

        assertTrue(guard.isLocked("alice", "10.0.0.1"));
    }

    /**
     * 账号维度差一次未达阈值时不锁定。
     *
     * <p>守护 {@code isLocked} 的阈值边界：放宽成 {@code >} 或把比较方向写反即失败。
     */
    @Test
    void isLockedReturnsFalseWhenAccountBelowThreshold() {
        when(redisCounter.get(ACCOUNT_KEY)).thenReturn(properties.getFailThreshold() - 1L);
        when(redisCounter.get(IP_KEY)).thenReturn(0L);

        assertFalse(guard.isLocked("alice", "10.0.0.1"));
    }

    /**
     * IP 维度跨账号累计达到阈值即锁定。
     *
     * <p>删掉 {@code isLocked} 里的 IP 维度判断即失败。
     */
    @Test
    void isLockedReturnsTrueWhenIpReachesThreshold() {
        when(redisCounter.get(ACCOUNT_KEY)).thenReturn(0L);
        when(redisCounter.get(IP_KEY)).thenReturn((long) properties.getIpFailThreshold());

        assertTrue(guard.isLocked("alice", "10.0.0.1"));
    }

    /**
     * 记一次失败：账号与 IP 两个维度都按锁定窗口计数，键格式与 TTL 均按约定。
     *
     * <p>守护 {@code recordFailure}：少计一个维度、或把 TTL 换成其它时长即失败。
     */
    @Test
    void recordFailureCountsBothDimensionsWithLockWindow() {
        properties.setLockMinutes(15);
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);

        guard.recordFailure("alice", "10.0.0.1");

        verify(redisCounter, times(2)).increment(keys.capture(), ttl.capture());
        assertEquals(List.of(ACCOUNT_KEY, IP_KEY), keys.getAllValues());
        assertEquals(Duration.ofMinutes(15), ttl.getValue());
    }

    /**
     * 防护关闭时不计任何失败。
     *
     * <p>去掉 {@code recordFailure} 的开关判断、或漏写提前返回即失败。
     */
    @Test
    void recordFailureDoesNothingWhenGuardDisabled() {
        properties.setEnabled(false);

        guard.recordFailure("alice", "10.0.0.1");

        verifyNoInteractions(redisCounter);
    }

    /**
     * 登录成功只清零账号维度，IP 维度保留（防止换账号继续撞库）。
     *
     * <p>把 {@code clearAccount} 改成同时清 IP 维度即失败。
     */
    @Test
    void clearAccountResetsAccountKeyOnly() {
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);

        guard.clearAccount("alice", "10.0.0.1");

        verify(redisCounter).reset(keys.capture());
        assertEquals(List.of(ACCOUNT_KEY), keys.getAllValues());
        verify(redisCounter, never()).reset(IP_KEY);
    }

    /**
     * 防护关闭时不清零。
     *
     * <p>去掉 {@code clearAccount} 的开关判断即失败。
     */
    @Test
    void clearAccountDoesNothingWhenGuardDisabled() {
        properties.setEnabled(false);

        guard.clearAccount("alice", "10.0.0.1");

        verifyNoInteractions(redisCounter);
    }
}
