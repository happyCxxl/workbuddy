package com.workbuddy.filecenter.service.impl;

import com.workbuddy.common.domain.input.FileMetadata;
import com.workbuddy.filecenter.storage.FileStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 文件下载服务单测。
 *
 * <p>守护 {@code download} 的响应契约：附件头按 UTF-8 编码文件名、内容类型为二进制流、
 * 有大小才写 Content-Length、内容原样写入响应。
 */
@ExtendWith(MockitoExtension.class)
class FileServiceImplTest {

    @Mock
    private FileStorage fileStorage;

    /**
     * 完整元数据：头部齐全且内容逐字节写入。
     *
     * <p>守护 {@code download} 的成功路径：文件名编码或内容类型错会让浏览器下载成乱码/网页。
     */
    @Test
    void downloadShouldWriteHeadersAndContent() throws Exception {
        FileMetadata metadata = new FileMetadata();
        metadata.setFileName("招标文件.pdf");
        metadata.setFileSize(5L);
        when(fileStorage.metadata("f1")).thenReturn(metadata);
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        when(fileStorage.open("f1")).thenReturn(new ByteArrayInputStream(content));
        MockHttpServletResponse response = new MockHttpServletResponse();

        new FileServiceImpl(fileStorage).download("f1", response);

        assertTrue(response.getHeader("Content-Disposition").startsWith("attachment"), 
                response.getHeader("Content-Disposition"));
        assertEquals("application/octet-stream", response.getContentType());
        assertEquals(5L, response.getContentLengthLong());
        assertEquals("hello", response.getContentAsString());
    }

    /**
     * 大小为空：不写 Content-Length（未知长度交给容器分块）。
     *
     * <p>守护 {@code download} 的可空大小分支：写 0 会让浏览器截断下载。
     */
    @Test
    void downloadWithoutSizeShouldOmitContentLength() throws Exception {
        FileMetadata metadata = new FileMetadata();
        metadata.setFileName("a.txt");
        when(fileStorage.metadata("f2")).thenReturn(metadata);
        when(fileStorage.open("f2")).thenReturn(new ByteArrayInputStream(new byte[0]));
        MockHttpServletResponse response = new MockHttpServletResponse();

        new FileServiceImpl(fileStorage).download("f2", response);

        assertNull(response.getHeader("Content-Length"));
    }
}
