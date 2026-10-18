package com.workbuddy.auth.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workbuddy.common.web.R;
import com.workbuddy.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 未认证响应处理：请求未携带（或携带无效）令牌时，Security 的 AuthorizationFilter
 * 会抛 AccessDeniedException 并被拒绝，默认返回裸 403 且响应体为空——浏览器会把这段
 * 裸响应直接当页面渲染成 HTTP ERROR 403，前端也拿不到任何 code 可判断。
 *
 * <p>这里与 {@link RestAccessDeniedHandler} 同款口径：统一成 R 响应体 + HTTP 200，
 * 响应体带 {@code code=40101}（未认证）。
 * 两者区别：本类处理「没登录」，RestAccessDeniedHandler 处理「登录了但无权限」。
 *
 * @author cxxl
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        log.warn("===> RestAuthenticationEntryPoint 未认证, uri={}", request.getRequestURI());
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        R<Void> body = new R<>(ErrorCode.UNAUTHORIZED.getCode(), ErrorCode.UNAUTHORIZED.getMessage(), null,
                System.currentTimeMillis());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
