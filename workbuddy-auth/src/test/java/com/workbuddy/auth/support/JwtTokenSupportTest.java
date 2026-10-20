package com.workbuddy.auth.support;

import com.workbuddy.auth.config.JwtProperties;
import com.workbuddy.common.enums.user.UserRole;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.security.KnowledgeUser;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JWT 令牌支撑测试。
 */
class JwtTokenSupportTest {

    private static final String SECRET = "test-secret-0123456789-abcdefghijklmnopqrstuvwxyz";

    private static final Duration TTL = Duration.ofDays(2);

    /**
     * 守护 {@code signAndParseShouldRoundTrip}：该场景行为改动即失败。
     */
    @Test
    void signAndParseShouldRoundTrip() {
        JwtTokenSupport jwtUtil = jwtUtil(7);

        String token = jwtUtil.sign(100L, "admin", UserRole.ADMIN, 3, TTL);
        KnowledgeUser user = jwtUtil.parse(token);

        assertEquals(100L, user.getId());
        assertEquals("admin", user.getUsername());
        assertEquals(UserRole.ADMIN, user.getRole());
        assertEquals(3, user.getTokenVersion());
    }

    /**
     * 守护 {@code signShouldApplyGivenTtl}：该场景行为改动即失败。
     */
    @Test
    void signShouldApplyGivenTtl() {
        JwtTokenSupport jwtUtil = jwtUtil(7);

        KnowledgeUser user = jwtUtil.parse(jwtUtil.sign(100L, "admin", UserRole.ADMIN, 0, TTL));

        // 到期时刻应落在 now + TTL 附近（留 5 秒容差）
        assertTrue(user.getExpiresAt().isAfter(Instant.now().plus(TTL).minusSeconds(5)),
                "到期时刻应接近 now + TTL，实际: " + user.getExpiresAt());
    }

    /**
     * 守护 {@code ttlShouldFollowConfiguration}：该场景行为改动即失败。
     */
    @Test
    void ttlShouldFollowConfiguration() {
        JwtTokenSupport jwtUtil = new JwtTokenSupport(jwtProperties(1));

        assertEquals(Duration.ofDays(1), jwtUtil.standardTtl());
        assertEquals(Duration.ofDays(30), jwtUtil.rememberTtl());
    }

    /**
     * 守护 {@code parseShouldReturnUserRoleForPlainUser}：该场景行为改动即失败。
     */
    @Test
    void parseShouldReturnUserRoleForPlainUser() {
        JwtTokenSupport jwtUtil = jwtUtil(7);

        KnowledgeUser user = jwtUtil.parse(jwtUtil.sign(101L, "tom", UserRole.USER, 0, TTL));

        assertEquals(UserRole.USER, user.getRole());
        assertEquals(0, user.getTokenVersion());
    }

    /**
     * 守护 {@code parseShouldTreatNullTokenVersionAsZero}：该场景行为改动即失败。
     */
    @Test
    void parseShouldTreatNullTokenVersionAsZero() {
        JwtTokenSupport jwtUtil = jwtUtil(7);

        // tokenVersion 为 null 时按 0 处理，与库表默认值一致
        KnowledgeUser user = jwtUtil.parse(jwtUtil.sign(102L, "tom", UserRole.USER, null, TTL));

        assertEquals(0, user.getTokenVersion());
    }

    /**
     * 守护 {@code parseShouldThrowWhenTokenTampered}：该场景行为改动即失败。
     */
    @Test
    void parseShouldThrowWhenTokenTampered() {
        JwtTokenSupport jwtUtil = jwtUtil(7);
        String token = jwtUtil.sign(100L, "admin", UserRole.ADMIN, 0, TTL);

        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> jwtUtil.parse(token.substring(0, token.length() - 3) + "abc"));

        assertEquals(ErrorCode.UNAUTHORIZED, e.getErrorCode());
    }

    /**
     * 守护 {@code parseShouldThrowWhenTokenExpired}：该场景行为改动即失败。
     */
    @Test
    void parseShouldThrowWhenTokenExpired() {
        JwtTokenSupport jwtUtil = jwtUtil(0);
        // 负有效期：签发即过期，不依赖执行耗时
        String token = jwtUtil.sign(100L, "admin", UserRole.ADMIN, 0, Duration.ofSeconds(-1));

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> jwtUtil.parse(token));

        assertEquals(ErrorCode.UNAUTHORIZED, e.getErrorCode());
    }

    private JwtTokenSupport jwtUtil(long ttlDays) {
        return new JwtTokenSupport(jwtProperties(ttlDays));
    }

    /** 令牌配置替身：密钥固定，记住我档固定 30 天 */
    private JwtProperties jwtProperties(long ttlDays) {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(SECRET);
        properties.setTtlDays(ttlDays);
        properties.setTtlDaysRemember(30);
        return properties;
    }
}
