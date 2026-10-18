package com.workbuddy.auth.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workbuddy.common.web.R;
import com.workbuddy.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 鉴权失败响应处理：Security 的拒绝发生在 Controller 之前，
 * 不经过 {@code GlobalExceptionHandler}，这里统一成 R 响应体与 200 状态，
 * 避免前端拿到裸 403 而无法按 code 语义处理。
 *
 * @author cxxl
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        log.warn("===> RestAccessDeniedHandler 权限不足, uri={}", request.getRequestURI());
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        R<Void> body = new R<>(ErrorCode.FORBIDDEN.getCode(), ErrorCode.FORBIDDEN.getMessage(), null,
                System.currentTimeMillis());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
