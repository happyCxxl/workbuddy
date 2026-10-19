package com.workbuddy.common.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 头像地址口径单测。
 *
 * <p>守护 {@code readUrl}/{@code version} 的两条契约：未设置头像返回 null（前端回落姓名首字）、
 * 版本段只取文件名去扩展名（路径每次上传都变，用于让浏览器认出是新图）。
 */
class AvatarUrlUtilTest {

    /**
     * 未设置头像（用户或对象键缺失）返回 null。
     *
     * <p>守护 {@code readUrl} 的空值兜底：返回半截地址会让前端一直请求 404。
     */
    @Test
    void readUrlShouldReturnNullWhenUserOrKeyMissing() {
        assertNull(AvatarUrlUtil.readUrl(null, "avatar/1/a.png"));
        assertNull(AvatarUrlUtil.readUrl(1L, null));
        assertNull(AvatarUrlUtil.readUrl(1L, " "));
    }

    /**
     * 正常地址形如 {@code /user/avatar/{id}?v={版本段}}。
     *
     * <p>守护 {@code readUrl} 的拼接：路径前缀或版本参数错位会让浏览器缓存失效。
     */
    @Test
    void readUrlShouldCarryUserAndVersion() {
        assertEquals("/user/avatar/123?v=a3f9c2d1",
                AvatarUrlUtil.readUrl(123L, "avatar/123/a3f9c2d1.png"));
    }

    /**
     * 版本段：取最后一段文件名去扩展名；无扩展名时原样返回。
     *
     * <p>守护 {@code version} 的边界：点号在首位或无点号时不能截出空串。
     */
    @Test
    void versionShouldStripExtensionFromFileName() {
        assertEquals("", AvatarUrlUtil.version(null));
        assertEquals("", AvatarUrlUtil.version(" "));
        assertEquals("a3f9c2d1", AvatarUrlUtil.version("avatar/123/a3f9c2d1.png"));
        assertEquals("noext", AvatarUrlUtil.version("avatar/123/noext"));
        assertEquals(".hidden", AvatarUrlUtil.version("avatar/123/.hidden"));
        assertEquals("", AvatarUrlUtil.version("avatar/123/"));
    }
}
