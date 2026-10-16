package com.workbuddy.biz.controller.file;

import com.workbuddy.filecenter.service.FileService;
import com.workbuddy.filecenter.service.FileStorage;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文件接口路由测试：上传与下载两条路径的映射，以及未定义的路径不占位。
 *
 * <p>用独立 MockMvc 装配单个 Controller：不加载数据源与容器，只验证 URL 到方法的映射与收参绑定。
 *
 * @author cxxl
 */
class FileControllerRoutingTest {

    private FileStorage fileStorage;

    private FileService fileService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        fileStorage = mock(FileStorage.class);
        fileService = mock(FileService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new FileController(fileStorage, fileService)).build();
    }

    @Test
    void uploadPathShouldBindMultipartFileAndRouteToStorage() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "a.pdf", "application/pdf", new byte[] {1, 2});

        mockMvc.perform(multipart("/files").file(file)).andExpect(status().isOk());

        verify(fileStorage).putFile(any());
        verifyNoInteractions(fileService);
    }

    @Test
    void downloadPathShouldRouteToService() throws Exception {
        mockMvc.perform(get("/files/F-88")).andExpect(status().isOk());

        verify(fileService).download(eq("F-88"), any());
        verify(fileStorage, never()).putFile(any());
    }

    @Test
    void unexpectedRequestFormsShouldNotReachHandler() throws Exception {
        // 根路径只映射上传（POST）：GET 命中同一路径的别的谓词，回方法不允许而不是找不到
        mockMvc.perform(get("/files")).andExpect(status().isMethodNotAllowed());
        // 下载只认单段 fileId：多一段的路径没有映射
        mockMvc.perform(get("/files/F-88/raw")).andExpect(status().isNotFound());
        // 上传只收 multipart：URL 编码表单进不了收参绑定，由 Spring 在派发期抛异常
        assertThrows(ServletException.class, () -> mockMvc.perform(post("/files").param("file", "F-88")));

        verifyNoInteractions(fileStorage);
    }
}
