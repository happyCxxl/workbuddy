package com.workbuddy.auth.security;

import com.workbuddy.auth.support.AuthCookieSupport;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

/**
 * CSRF 双提交校验：Cookie 认证的写请求必须把 CSRF Cookie 回传到请求头。
 */
@Component
@RequiredArgsConstructor
public class CsrfFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private static final Set<String> ANONYMOUS_PATHS = Set.of("/auth/login", "/auth/register");

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        if (SAFE_METHODS.contains(request.getMethod()) || ANONYMOUS_PATHS.contains(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }
        // 仅 Cookie 认证需要 CSRF；显式带 Authorization 的客户端不受 Cookie 自动携带影响
        String cookieToken = AuthCookieSupport.value(request.getCookies(), AuthCookieSupport.TOKEN_COOKIE);
        if (cookieToken == null) {
            filterChain.doFilter(request, response);
            return;
        }
        String header = request.getHeader(AuthCookieSupport.CSRF_HEADER);
        String cookie = AuthCookieSupport.value(request.getCookies(), AuthCookieSupport.CSRF_COOKIE);
        if (!matches(header, cookie)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":40104,\"msg\":\"CSRF 校验失败\",\"data\":null}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean matches(String header, String cookie) {
        if (header == null || cookie == null) {
            return false;
        }
        return MessageDigest.isEqual(header.getBytes(StandardCharsets.UTF_8),
                cookie.getBytes(StandardCharsets.UTF_8));
    }
}
