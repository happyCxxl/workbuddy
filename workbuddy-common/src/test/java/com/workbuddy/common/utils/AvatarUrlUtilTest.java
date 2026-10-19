package com.workbuddy.common.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 头像地址归一单测。
 *
 * <p>守护 {@code urlOrNull}：只放行对象存储的绝对 URL；空值与历史对象 key 一律按“没有头像”处理，
 * 避免前端把相对 key 当图片地址渲染成破图。
 */
class AvatarUrlUtilTest {

    /**
     * 绝对 URL 原样下发。
     *
     * <p>守护 {@code urlOrNull} 的放行分支。
     */
    @Test
    void urlOrNullShouldPassAbsoluteUrl() {
        String url = "http://127.0.0.1:9000/files/avatar/1/a.png";
        assertEquals(url, AvatarUrlUtil.urlOrNull(url));
        assertEquals("https://cdn.example.com/a.png",
                AvatarUrlUtil.urlOrNull("https://cdn.example.com/a.png"));
    }

    /**
     * 空值与历史对象 key 归一为 null。
     *
     * <p>守护存量数据降级（JAVA-DTO-012：应用层只下发对象 URL）：旧 key 不再能通过应用层接口读取。
     */
    @Test
    void urlOrNullShouldRejectBlankAndLegacyKey() {
        assertNull(AvatarUrlUtil.urlOrNull(null));
        assertNull(AvatarUrlUtil.urlOrNull(""));
        assertNull(AvatarUrlUtil.urlOrNull("   "));
        assertNull(AvatarUrlUtil.urlOrNull("avatar/1/a.png"));
        assertNull(AvatarUrlUtil.urlOrNull("/user/avatar/1?v=abc"));
    }
}
