package com.workbuddy.auth.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workbuddy.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.access.AccessDeniedException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 认证 / 鉴权失败响应单测。
 *
 * <p>守护两个处理器的统一口径：HTTP 状态保持 200、响应体是 R 结构、
 * 未认证码 40101、无权限码 40104；前端按 code 分支，状态码或码值错会显示成 403 白页。
 */
class RestAuthHandlersTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 未认证：200 + code=40101。
     *
     * <p>守护 {@code RestAuthenticationEntryPoint.commence}。
     */
    @Test
    void entryPointShouldWriteUnauthorizedBody() throws Exception {
        RestAuthenticationEntryPoint entryPoint = new RestAuthenticationEntryPoint(objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/x");
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(request, response, new BadCredentialsException("no token"));

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/json"));
        String body = response.getContentAsString();
        assertTrue(body.contains(String.valueOf(ErrorCode.UNAUTHORIZED.getCode())), body);
        assertTrue(body.contains("code"), body);
    }

    /**
     * 已登录但无权限：200 + code=40104。
     *
     * <p>守护 {@code RestAccessDeniedHandler.handle}。
     */
    @Test
    void deniedHandlerShouldWriteForbiddenBody() throws Exception {
        RestAccessDeniedHandler handler = new RestAccessDeniedHandler(objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request, response, new AccessDeniedException("denied"));

        assertEquals(200, response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains(String.valueOf(ErrorCode.FORBIDDEN.getCode())), body);
    }
}
