package com.workbuddy.auth.service.support;

import com.workbuddy.auth.config.LoginGuardProperties;
import com.workbuddy.infra.redis.RedisCounter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 登录失败防护：按「账号 + IP」与「IP」两个维度计数，达阈值即拒绝登录。
 *
 * @author cxxl
 */
@Component
@RequiredArgsConstructor
public class LoginAttemptGuard {

    /** 账号 + IP 维度计数键前缀 */
    private static final String ACCOUNT_KEY_PREFIX = "knowledge:auth:fail:account:";

    /** IP 维度计数键前缀 */
    private static final String IP_KEY_PREFIX = "knowledge:auth:fail:ip:";

    private final RedisCounter redisCounter;

    private final LoginGuardProperties properties;

    /** 该「账号 + IP」或该 IP 是否已达失败阈值 */
    public boolean isLocked(String username, String ip) {
        if (!properties.isEnabled()) {
            return false;
        }
        return redisCounter.get(accountKey(username, ip)) >= properties.getFailThreshold()
                || redisCounter.get(ipKey(ip)) >= properties.getIpFailThreshold();
    }

    /** 记一次失败：两个维度都计数；账号不存在时同样计数 */
    public void recordFailure(String username, String ip) {
        if (!properties.isEnabled()) {
            return;
        }
        Duration window = Duration.ofMinutes(properties.getLockMinutes());
        redisCounter.increment(accountKey(username, ip), window);
        redisCounter.increment(ipKey(ip), window);
    }

    /** 登录成功后清零账号维度；IP 维度保留 */
    public void clearAccount(String username, String ip) {
        if (properties.isEnabled()) {
            redisCounter.reset(accountKey(username, ip));
        }
    }

    private String accountKey(String username, String ip) {
        return ACCOUNT_KEY_PREFIX + username + ":" + ip;
    }

    private String ipKey(String ip) {
        return IP_KEY_PREFIX + ip;
    }
}
