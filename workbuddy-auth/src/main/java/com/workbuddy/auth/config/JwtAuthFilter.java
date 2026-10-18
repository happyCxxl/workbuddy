package com.workbuddy.auth.config;

import com.workbuddy.auth.db.WbUserDbService;
import com.workbuddy.auth.util.JwtUtil;
import com.workbuddy.common.security.KnowledgeUser;
import com.workbuddy.common.utils.NullUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * JWT 认证过滤器：解析请求令牌并写入安全上下文。
 */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    /** 令牌头前缀 */
    private static final String BEARER_PREFIX = "Bearer ";

    /** Spring Security 角色权限前缀 */
    private static final String ROLE_PREFIX = "ROLE_";

    private final JwtUtil jwtUtil;

    private final WbUserDbService userDbService;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (NullUtil.isNotNull(header) && header.startsWith(BEARER_PREFIX)) {
            try {
                KnowledgeUser user = jwtUtil.parse(header.substring(BEARER_PREFIX.length()));
                if (isTokenStillValid(user)) {
                    // 角色写入权限集（ROLE_ADMIN / ROLE_USER），供 requestMatchers(...hasRole) 判定
                    SimpleGrantedAuthority authority =
                            new SimpleGrantedAuthority(ROLE_PREFIX + user.getRole().getCode());
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(user, null, List.of(authority));
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                } else {
                    SecurityContextHolder.clearContext();
                }
            } catch (Exception e) {
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }

    /**
     * 令牌是否仍然有效：比对令牌载荷中的版本号与库中当前值。
     *
     * <p>改密码 / 改角色 / 停用 / 删除后库值递增，已签发的旧令牌立即失效，
     * 不必等令牌自然过期。代价是每个带令牌的请求多一次主键查询
     * （单表主键命中，平台规模下可接受；若成为热点可加短 TTL 缓存）。
     */
    private boolean isTokenStillValid(KnowledgeUser user) {
        if (NullUtil.isNull(user.getId())) {
            return false;
        }
        Integer currentVersion = userDbService.findTokenVersion(user.getId());
        if (NullUtil.isNull(currentVersion)) {
            // 用户已被删除（逻辑删除后查不到）
            return false;
        }
        Integer tokenVersion = user.getTokenVersion();
        return currentVersion.equals(Objects.requireNonNullElse(tokenVersion, 0));
    }
}
