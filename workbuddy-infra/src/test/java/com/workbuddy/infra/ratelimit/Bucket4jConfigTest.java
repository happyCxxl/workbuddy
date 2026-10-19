package com.workbuddy.infra.ratelimit;

import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.lettuce.core.AbstractRedisClient;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.RedisCodec;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 限流桶装配单测。
 *
 * <p>守护 {@code rateLimitConnection}/{@code rateLimitProxyManager} 的装配契约：
 * 复用 Spring Data Redis 的原生客户端（不新建第二个 Redis 客户端），集群客户端必须明确拒绝。
 */
class Bucket4jConfigTest {

    private final Bucket4jConfig config = new Bucket4jConfig();

    /**
     * 单机客户端：借用其连接建立字节编解码的桶状态连接。
     *
     * <p>守护 {@code rateLimitConnection}：连接类型或编解码器错会让桶状态与业务键混用。
     */
    @Test
    @SuppressWarnings("unchecked")
    void rateLimitConnectionShouldReuseNativeClient() {
        RedisClient redisClient = mock(RedisClient.class);
        StatefulRedisConnection<String, byte[]> connection = mock(StatefulRedisConnection.class);
        when(redisClient.connect(any(RedisCodec.class))).thenReturn(connection);
        LettuceConnectionFactory factory = mock(LettuceConnectionFactory.class);
        when(factory.getRequiredNativeClient()).thenReturn(redisClient);

        assertSame(connection, config.rateLimitConnection(factory));
    }

    /**
     * 集群客户端：明确拒绝并指出实际类型。
     *
     * <p>守护 {@code redisClient} 的类型校验：静默降级会让限流在集群下失效。
     */
    @Test
    void rateLimitConnectionShouldRejectClusterClient() {
        AbstractRedisClient clusterClient = mock(AbstractRedisClient.class);
        LettuceConnectionFactory factory = mock(LettuceConnectionFactory.class);
        when(factory.getRequiredNativeClient()).thenReturn(clusterClient);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> config.rateLimitConnection(factory));

        org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("单机"), e.getMessage());
    }

}
