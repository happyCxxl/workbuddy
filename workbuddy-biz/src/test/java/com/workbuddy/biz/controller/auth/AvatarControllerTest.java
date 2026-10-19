package com.workbuddy.biz.controller.auth;

import com.workbuddy.biz.service.AvatarService;
import com.workbuddy.common.web.R;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 头像接口单测。
 *
 * <p>守护三件事：写操作只交服务处理、读取无头像回 404、读取命中时按对象键后缀给出
 * Content-Type / ETag / 长缓存头（浏览器直接渲染，不能回 JSON 外壳）。
 */
class AvatarControllerTest {

    private static final byte[] IMAGE = {1, 2, 3};

    private AvatarService avatarService;

    private AvatarController controller;

    /**
     * 每个用例重建控制器。
     */
    @BeforeEach
    void setUp() {
        avatarService = mock(AvatarService.class);
        controller = new AvatarController(avatarService);
    }

    /**
     * 上传返回服务给出的新地址，移除交给服务。
     *
     * <p>守护 {@code upload}/{@code remove} 的委托：userId 由服务从上下文取，控制器不掺和。
     */
    @Test
    void writeEndpointsShouldDelegateToService() {
        MultipartFile file = mock(MultipartFile.class);
        when(avatarService.upload(file)).thenReturn("/user/avatar/7?v=2");

        R<String> response = controller.upload(file);
        controller.remove();

        assertEquals("/user/avatar/7?v=2", response.getData());
        assertEquals(R.SUCCESS, response.getCode());
        verify(avatarService).upload(file);
        verify(avatarService).remove();
    }

    /**
     * 没有头像回 404，且不带内容类型与 ETag。
     *
     * <p>守护 {@code read} 的缺失分支：回 200 空体会让浏览器渲染出破图。
     */
    @Test
    void readShouldReturnNotFoundWithoutAvatar() {
        when(avatarService.load(7L)).thenReturn(null);

        ResponseEntity<byte[]> response = controller.read(7L);

        assertEquals(404, response.getStatusCode().value());
        assertTrue(response.getBody() == null || response.getBody().length == 0);
    }

    /**
     * 读取命中时回对象字节、按后缀给 Content-Type、ETag 取对象键、缓存一年。
     *
     * <p>守护 {@code read} 的响应头组装：缺 ETag 或缓存头会让头像每次刷新都重新拉取。
     */
    @Test
    void readShouldServeBytesWithCacheHeaders() {
        when(avatarService.load(7L)).thenReturn(
                new AvatarService.AvatarContent("user/u7/avatar.PNG", IMAGE));

        ResponseEntity<byte[]> response = controller.read(7L);

        assertEquals(200, response.getStatusCode().value());
        assertArrayEquals(IMAGE, response.getBody());
        assertEquals(MediaType.IMAGE_PNG, response.getHeaders().getContentType());
        assertEquals("\"user/u7/avatar.PNG\"", response.getHeaders().getETag());
        String cacheControl = response.getHeaders().getCacheControl();
        assertTrue(cacheControl.contains("max-age=31536000"), cacheControl);
        assertTrue(cacheControl.contains("public"), cacheControl);
        assertTrue(cacheControl.contains("immutable"), cacheControl);
    }

    /**
     * 后缀决定响应类型：webp 走 webp，其余（含无后缀）按 jpeg 兜底。
     *
     * <p>守护 {@code mediaTypeOf} 的三条分支：全判成 jpeg 会让 webp 头像错显。
     */
    @Test
    void readShouldDeriveMediaTypeFromSuffix() {
        when(avatarService.load(8L)).thenReturn(
                new AvatarService.AvatarContent("user/u8/avatar.webp", IMAGE));
        when(avatarService.load(9L)).thenReturn(
                new AvatarService.AvatarContent("user/u9/avatar", IMAGE));

        assertEquals(MediaType.parseMediaType("image/webp"),
                controller.read(8L).getHeaders().getContentType());
        assertEquals(MediaType.IMAGE_JPEG,
                controller.read(9L).getHeaders().getContentType());
    }
}
