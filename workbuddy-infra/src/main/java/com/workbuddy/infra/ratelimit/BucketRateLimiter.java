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
        Bucket bucket = rateLimitProxyManager.builder().build(key, () -> configuration(capacity, periodSeconds));
        return bucket.tryConsume(1);
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
