package com.workbuddy.infra.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 令牌桶限流器：桶按 key 存放在 Redis，跨实例共享（由 bucket4j 的分布式代理完成）。
 */
@Component
@RequiredArgsConstructor
public class BucketRateLimiter {

    private final ProxyManager<String> rateLimitProxyManager;

    /**
     * 尝试消耗一个令牌。
     *
     * @param key           桶的键
     * @param capacity      窗口内允许的次数
     * @param periodSeconds 窗口长度（秒）
     * @return 取到令牌返回 true；窗口内次数已用尽返回 false
     */
    public boolean tryAcquire(String key, int capacity, int periodSeconds) {
        return tryConsume(key, 1, capacity, periodSeconds);
    }

    /**
     * 尝试消耗指定数量的令牌（TPM 按 token 数消费）。
     *
     * @param key           桶的键
     * @param tokens        本次消耗的令牌数
     * @param capacity      窗口内允许的令牌总数
     * @param periodSeconds 窗口长度（秒）
     * @return 取到令牌返回 true；余量不足返回 false
     */
    public boolean tryConsume(String key, long tokens, int capacity, int periodSeconds) {
        return bucket(key, capacity, periodSeconds).tryConsume(tokens);
    }

    /**
     * 当前窗口剩余令牌数（容量打分与快照用）。
     *
     * @param key           桶的键
     * @param capacity      窗口内允许的令牌总数
     * @param periodSeconds 窗口长度（秒）
     * @return 剩余令牌数（可能为 0）
     */
    public long availableTokens(String key, int capacity, int periodSeconds) {
        return bucket(key, capacity, periodSeconds).getAvailableTokens();
    }

    /**
     * 归还令牌（组合扣减失败时回滚已扣部分）。
     *
     * @param key           桶的键
     * @param tokens        归还数量
     * @param capacity      窗口内允许的令牌总数
     * @param periodSeconds 窗口长度（秒）
     */
    public void addTokens(String key, long tokens, int capacity, int periodSeconds) {
        bucket(key, capacity, periodSeconds).addTokens(tokens);
    }

    /**
     * 无条件消耗令牌（用于调用后按真实 usage 记账；即使本次超出余量也扣，靠窗口回填恢复）。
     *
     * @param key           桶的键
     * @param tokens        消耗数量
     * @param capacity      窗口内允许的令牌总数
     * @param periodSeconds 窗口长度（秒）
     */
    public void consumeIgnoringRateLimits(String key, long tokens, int capacity, int periodSeconds) {
        bucket(key, capacity, periodSeconds).consumeIgnoringRateLimits(tokens);
    }

    private Bucket bucket(String key, int capacity, int periodSeconds) {
        return rateLimitProxyManager.builder().build(key, () -> configuration(capacity, periodSeconds));
    }

    private BucketConfiguration configuration(int capacity, int periodSeconds) {
        return BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(capacity)
                        // 整桶按窗口回填：语义即「每 periodSeconds 秒最多 capacity 次」
                        .refillIntervally(capacity, Duration.ofSeconds(periodSeconds))
                        .build())
                .build();
    }
}
