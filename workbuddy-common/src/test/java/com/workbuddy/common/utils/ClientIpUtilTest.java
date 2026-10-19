package com.workbuddy.common.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 客户端 IP 工具测试。
 */
class ClientIpUtilTest {

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    /**
     * 守护 {@code shouldReturnRemoteAddrWhenRequestPresent}：该场景行为改动即失败。
     */
    @Test
    void shouldReturnRemoteAddrWhenRequestPresent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertEquals("10.0.0.1", ClientIpUtil.getClientIp());
    }

    /**
     * 守护 {@code shouldReturnUnknownWhenNoRequestContext}：该场景行为改动即失败。
     */
    @Test
    void shouldReturnUnknownWhenNoRequestContext() {
        assertEquals("unknown", ClientIpUtil.getClientIp());
    }
}
