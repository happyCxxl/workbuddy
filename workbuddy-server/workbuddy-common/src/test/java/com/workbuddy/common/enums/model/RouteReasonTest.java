package com.workbuddy.common.enums.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 模型路由原因单测。
 *
 * <p>守护 {@code of} 的码值解析：路由原因是模型选择留痕的维度之一。
 */
class RouteReasonTest {

    /**
     * 每个原因都能按码值解析回来。
     *
     * <p>守护 {@code of}：码值改错即失败（路由留痕解析不了）。
     */
    @Test
    void ofShouldResolveEveryCode() {
        for (RouteReason reason : RouteReason.values()) {
            assertEquals(reason, RouteReason.of(reason.name()), () -> reason + " 按码值解析失败");
        }
        assertNull(RouteReason.of("NO_SUCH"));
    }
}
