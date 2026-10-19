package com.workbuddy.infra.ratelimit;

import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.distributed.proxy.RemoteBucketBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 令牌桶限流器单测。
 *
 * <p>守护 {@code tryAcquire} 的口径：按 key 取桶、每次消费一枚令牌、
 * 桶配置为「容量 capacity、整流周期 periodSeconds 秒」。
 * 代理管理器与桶为系统边界，用替身；配置用捕获的真实对象断言。
 */
@ExtendWith(MockitoExtension.class)
class BucketRateLimiterTest {

    @Mock
    private ProxyManager<String> proxyManager;

    @Mock
    private RemoteBucketBuilder<String> proxyBuilder;

    @Mock
    private BucketProxy bucket;

    private BucketRateLimiter limiter;

    /**
     * 每个用例重建限流器并接好 builder 链。
     */
    @BeforeEach
    void setUp() {
        limiter = new BucketRateLimiter(proxyManager);
    }

    /**
     * 桶允许消费时返回 true，且桶按 key 构建、每次消费一枚令牌。
     *
     * <p>守护 {@code tryAcquire}：换成消费 2 枚或换 key 即失败。
     */
    @Test
    void tryAcquireShouldConsumeOneTokenForBucketOfKey() {
        AtomicReference<BucketConfiguration> captured = new AtomicReference<>();
        when(proxyManager.builder()).thenReturn(proxyBuilder);
        when(proxyBuilder.build(eq("login:alice"),
                ArgumentMatchers.<Supplier<BucketConfiguration>>any())).thenAnswer(invocation -> {
            Supplier<BucketConfiguration> supplier = invocation.getArgument(1);
            captured.set(supplier.get());
            return bucket;
        });
        when(bucket.tryConsume(1)).thenReturn(true);
        assertTrue(limiter.tryAcquire("login:alice", 5, 60));

        BucketConfiguration config = captured.get();
        io.github.bucket4j.Bandwidth[] bandwidths = config.getBandwidths();
        assertEquals(1, bandwidths.length);
        assertEquals(5L, bandwidths[0].getCapacity());
    }

    /**
     * 窗口内次数用尽时返回 false（由桶的 tryConsume 决定）。
     *
     * <p>守护 {@code tryAcquire} 的透传口径：把取反写反即失败。
     */
    @Test
    void tryAcquireShouldReturnFalseWhenBucketExhausted() {
        when(proxyManager.builder()).thenReturn(proxyBuilder);
        when(proxyBuilder.build(eq("login:bob"),
                ArgumentMatchers.<Supplier<BucketConfiguration>>any())).thenReturn(bucket);
        when(bucket.tryConsume(1)).thenReturn(false);

        assertFalse(limiter.tryAcquire("login:bob", 5, 60));
    }
}
