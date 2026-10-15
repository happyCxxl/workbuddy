package com.workbuddy.auth.util;

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
 * JWT 令牌工具测试。
 *
 * @author cxxl
 */
class JwtUtilTest {

    private static final String SECRET = "test-secret-0123456789-abcdefghijklmnopqrstuvwxyz";

    private static final Duration TTL = Duration.ofDays(2);

    @Test
    void signAndParseShouldRoundTrip() {
        JwtUtil jwtUtil = jwtUtil(7);

        String token = jwtUtil.sign(100L, "admin", UserRole.ADMIN, 3, TTL);
        KnowledgeUser user = jwtUtil.parse(token);

        assertEquals(100L, user.getId());
        assertEquals("admin", user.getUsername());
        assertEquals(UserRole.ADMIN, user.getRole());
        assertEquals(3, user.getTokenVersion());
    }

    @Test
    void signShouldApplyGivenTtl() {
        JwtUtil jwtUtil = jwtUtil(7);

        KnowledgeUser user = jwtUtil.parse(jwtUtil.sign(100L, "admin", UserRole.ADMIN, 0, TTL));

        // 到期时刻应落在 now + TTL 附近（留 5 秒容差）
        assertTrue(user.getExpiresAt().isAfter(Instant.now().plus(TTL).minusSeconds(5)),
                "到期时刻应接近 now + TTL，实际: " + user.getExpiresAt());
    }

    @Test
    void ttlShouldFollowConfiguration() {
        JwtUtil jwtUtil = new JwtUtil(SECRET, 1, 30);

        assertEquals(Duration.ofDays(1), jwtUtil.standardTtl());
        assertEquals(Duration.ofDays(30), jwtUtil.rememberTtl());
    }

    @Test
    void parseShouldReturnUserRoleForPlainUser() {
        JwtUtil jwtUtil = jwtUtil(7);

        KnowledgeUser user = jwtUtil.parse(jwtUtil.sign(101L, "tom", UserRole.USER, 0, TTL));

        assertEquals(UserRole.USER, user.getRole());
        assertEquals(0, user.getTokenVersion());
    }

    @Test
    void parseShouldTreatNullTokenVersionAsZero() {
        JwtUtil jwtUtil = jwtUtil(7);

        // tokenVersion 为 null 时按 0 处理，与库表默认值一致
        KnowledgeUser user = jwtUtil.parse(jwtUtil.sign(102L, "tom", UserRole.USER, null, TTL));

        assertEquals(0, user.getTokenVersion());
    }

    @Test
    void parseShouldThrowWhenTokenTampered() {
        JwtUtil jwtUtil = jwtUtil(7);
        String token = jwtUtil.sign(100L, "admin", UserRole.ADMIN, 0, TTL);

        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> jwtUtil.parse(token.substring(0, token.length() - 3) + "abc"));

        assertEquals(ErrorCode.UNAUTHORIZED, e.getErrorCode());
    }

    @Test
    void parseShouldThrowWhenTokenExpired() {
        JwtUtil jwtUtil = jwtUtil(0);
        // 负有效期：签发即过期，不依赖执行耗时
        String token = jwtUtil.sign(100L, "admin", UserRole.ADMIN, 0, Duration.ofSeconds(-1));

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> jwtUtil.parse(token));

        assertEquals(ErrorCode.UNAUTHORIZED, e.getErrorCode());
    }

    private JwtUtil jwtUtil(long ttlDays) {
        return new JwtUtil(SECRET, ttlDays, 30);
    }
}
