package com.workbuddy.auth.support;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * 登录 Cookie：令牌放 HttpOnly Cookie，另发一份可读的 CSRF Cookie（双提交校验）。
 */
@Component
public class AuthCookieSupport {

    /**
     * 令牌 Cookie 名（HttpOnly，前端不可读）
     */
    public static final String TOKEN_COOKIE = "wb_token";

    /**
     * CSRF Cookie 名（前端可读，值需回传请求头）
     */
    public static final String CSRF_COOKIE = "XSRF-TOKEN";

    /**
     * CSRF 请求头名
     */
    public static final String CSRF_HEADER = "X-CSRF-Token";

    private static final String PATH = "/";

    private final boolean secure;

    /**
     * 构造 Cookie 工具。
     *
     * @param secure 是否加 Secure 标记（HTTPS 部署置 true）
     */
    public AuthCookieSupport(@Value("${workbuddy.auth.cookie.secure:false}") boolean secure) {
        this.secure = secure;
    }

    /**
     * 写登录 Cookie：令牌 HttpOnly，有效期随「记住我」档位；同时刷新 CSRF Cookie。
     *
     * @param response HTTP 响应
     * @param token    令牌
     * @param ttl      有效期
     */
    public void write(HttpServletResponse response, String token, Duration ttl) {
        add(response, cookie(TOKEN_COOKIE, token, ttl, true));
        add(response, cookie(CSRF_COOKIE, UUID.randomUUID().toString(), ttl, false));
    }

    /**
     * 清登录 Cookie：令牌与 CSRF 一并失效。
     *
     * @param response HTTP 响应
     */
    public void clear(HttpServletResponse response) {
        add(response, cookie(TOKEN_COOKIE, "", Duration.ZERO, true));
        add(response, cookie(CSRF_COOKIE, "", Duration.ZERO, false));
    }

    /**
     * 取请求里的 Cookie 值。
     *
     * @param cookies Cookie 数组（可为空）
     * @param name    Cookie 名
     * @return 值；不存在返回 null
     */
    public static String value(Cookie[] cookies, String name) {
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private ResponseCookie cookie(String name, String value, Duration ttl, boolean httpOnly) {
        return ResponseCookie.from(name, value)
                .httpOnly(httpOnly)
                .secure(secure)
                .path(PATH)
                .sameSite("Lax")
                .maxAge(ttl)
                .build();
    }

    private void add(HttpServletResponse response, ResponseCookie cookie) {
        response.addHeader("Set-Cookie", cookie.toString());
    }
}
