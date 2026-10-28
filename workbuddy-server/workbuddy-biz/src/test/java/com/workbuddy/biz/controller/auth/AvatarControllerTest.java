package com.workbuddy.biz.controller.auth;

import com.workbuddy.biz.service.AvatarService;
import com.workbuddy.common.web.R;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 头像接口单测。
 *
 * <p>守护两件事：写操作只交服务处理；上传返回对象存储 URL，不再有应用层读取/代理字节的接口。
 */
class AvatarControllerTest {

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
     * 上传返回服务给出的对象 URL，移除交给服务。
     *
     * <p>守护 {@code upload}/{@code remove} 的委托：userId 由服务从上下文取，控制器不掺和。
     */
    @Test
    void writeEndpointsShouldDelegateToService() {
        MultipartFile file = mock(MultipartFile.class);
        String url = "http://127.0.0.1:9000/files/avatar/7/a.png";
        when(avatarService.upload(file)).thenReturn(url);

        R<String> response = controller.upload(file);
        controller.remove();

        assertEquals(url, response.getData());
        assertEquals(R.SUCCESS, response.getCode());
        verify(avatarService).upload(file);
        verify(avatarService).remove();
    }
}
