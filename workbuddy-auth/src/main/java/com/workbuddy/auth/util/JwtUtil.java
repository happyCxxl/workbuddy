package com.workbuddy.auth.util;

import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTPayload;
import cn.hutool.jwt.JWTUtil;
import com.workbuddy.auth.config.JwtProperties;
import com.workbuddy.common.enums.user.UserRole;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.security.KnowledgeUser;
import com.workbuddy.common.utils.NullUtil;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.Objects;

/**
 * JWT 令牌工具：签发与解析。
 */
@Component
public class JwtUtil {

    /**
     * 用户名载荷键
     */
    private static final String CLAIM_USERNAME = "username";

    /**
     * 角色载荷键
     */
    private static final String CLAIM_ROLE = "role";

    /**
     * 令牌版本载荷键
     */
    private static final String CLAIM_TOKEN_VERSION = "tv";

    private final byte[] secret;

    /**
     * 不勾选「记住我」的令牌有效期
     */
    private final Duration standardTtl;

    /**
     * 勾选「记住我」的令牌有效期
     */
    private final Duration rememberTtl;

    public JwtUtil(JwtProperties properties) {
        this.secret = properties.getSecret().getBytes(StandardCharsets.UTF_8);
        this.standardTtl = Duration.ofDays(properties.getTtlDays());
        this.rememberTtl = Duration.ofDays(properties.getTtlDaysRemember());
    }

    /**
     * 不勾选「记住我」的令牌有效期
     */
    public Duration standardTtl() {
        return standardTtl;
    }

    /**
     * 勾选「记住我」的令牌有效期
     */
    public Duration rememberTtl() {
        return rememberTtl;
    }

    /**
     * 签发令牌。
     *
     * @param userId       用户 ID（写入 subject）
     * @param username     用户名（写入载荷）
     * @param role         角色（写入载荷，供鉴权判定）
     * @param tokenVersion 令牌版本（写入载荷，供失效校验）
     * @param ttl          本次令牌的有效期
     * @return 令牌串
     */
    public String sign(Long userId, String username, UserRole role, Integer tokenVersion, Duration ttl) {
        return JWT.create()
                .setSubject(String.valueOf(userId))
                .setPayload(CLAIM_USERNAME, username)
                .setPayload(CLAIM_ROLE, role.getCode())
                .setPayload(CLAIM_TOKEN_VERSION, Objects.requireNonNullElse(tokenVersion, 0))
                .setExpiresAt(new Date(System.currentTimeMillis() + ttl.toMillis()))
                .setKey(secret)
                .sign();
    }

    /**
     * 解析令牌。
     *
     * @param token 令牌串
     * @return 用户模型（含令牌到期时刻）
     * @throws KnowledgeException 签名无效或已过期（UNAUTHORIZED）
     */
    public KnowledgeUser parse(String token) {
        try {
            JWT jwt = JWT.of(token);
            ThrowUtil.throwIf(!JWTUtil.verify(token, secret), ErrorCode.UNAUTHORIZED);
            Date expiresAt = jwt.getPayloads().getDate(JWTPayload.EXPIRES_AT);
            ThrowUtil.throwIf(NullUtil.isNull(expiresAt) || expiresAt.before(new Date()), ErrorCode.UNAUTHORIZED);
            KnowledgeUser user = new KnowledgeUser();
            user.setId(Long.valueOf(jwt.getPayload("sub").toString()));
            user.setUsername(jwt.getPayload(CLAIM_USERNAME).toString());
            // 旧令牌不含角色载荷：回落 USER，避免历史令牌直接提权
            Object role = jwt.getPayload(CLAIM_ROLE);
            user.setRole(UserRole.of(NullUtil.isNull(role) ? null : role.toString()));
            user.setTokenVersion(readTokenVersion(jwt));
            user.setExpiresAt(expiresAt.toInstant());
            return user;
        } catch (KnowledgeException e) {
            throw e;
        } catch (Exception e) {
            throw new KnowledgeException(ErrorCode.UNAUTHORIZED);
        }
    }

    /**
     * 读取令牌版本：缺失或非数字按 0 处理（与库表默认值一致）
     */
    private Integer readTokenVersion(JWT jwt) {
        Object value = jwt.getPayload(CLAIM_TOKEN_VERSION);
        if (NullUtil.isNull(value)) {
            return 0;
        }
        try {
            return Integer.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
