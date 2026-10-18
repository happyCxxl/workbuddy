package com.workbuddy.infra.ratelimit;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.AbstractRedisClient;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.time.Duration;

/**
 * 限流桶的 Redis 装配：复用工程已有的 Lettuce 客户端，不引入第二个 Redis 客户端。
 */
@Configuration
public class Bucket4jConfig {

    /** 桶在 Redis 中的最长存活时间：窗口过后自动回收，避免每个客户端一个 key 长期堆积 */
    private static final Duration BUCKET_TTL = Duration.ofHours(1);

    @Bean(destroyMethod = "close")
    public StatefulRedisConnection<String, byte[]> rateLimitConnection(LettuceConnectionFactory connectionFactory) {
        return redisClient(connectionFactory).connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
    }

    @Bean
    public ProxyManager<String> rateLimitProxyManager(StatefulRedisConnection<String, byte[]> rateLimitConnection) {
        return Bucket4jLettuce.casBasedBuilder(rateLimitConnection)
                .expirationAfterWrite(ExpirationAfterWriteStrategy
                        .basedOnTimeForRefillingBucketUpToMax(BUCKET_TTL))
                .build();
    }

    /**
     * 取 Spring Data Redis 的原生客户端做借用；集群客户端不支持（本工程为单机 Redis）。
     *
     * <p>该客户端归 {@link LettuceConnectionFactory} 所有、由容器关闭时释放，此处只借用、不关闭。
     */
    private RedisClient redisClient(LettuceConnectionFactory connectionFactory) {
        AbstractRedisClient nativeClient = connectionFactory.getRequiredNativeClient();
        if (nativeClient instanceof RedisClient redisClient) {
            return redisClient;
        }
        throw new IllegalStateException("接口限流需要单机 Redis 客户端，当前为 " + nativeClient.getClass().getName());
    }
}
