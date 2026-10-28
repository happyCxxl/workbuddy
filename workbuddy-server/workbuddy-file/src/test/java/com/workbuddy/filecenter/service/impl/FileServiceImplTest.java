package com.workbuddy.filecenter.service.impl;

import com.workbuddy.common.domain.input.FileMetadata;
import com.workbuddy.common.exception.WorkbuddyException;
import com.workbuddy.filecenter.storage.FileStorage;
import com.workbuddy.filecenter.config.FileProperties;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文件服务单测（上传 + 下载）。
 *
 * <p>守护 {@code download} 的响应契约：附件头按 UTF-8 编码文件名、内容类型为二进制流、
 * 有大小才写 Content-Length、有 sha256 才写 ETag、带禁缓存头、内容原样写入响应；
 * 守护 {@code upload} 的校验前置与落库。
 */
@ExtendWith(MockitoExtension.class)
class FileServiceImplTest {

    @Mock
    private FileStorage fileStorage;

    /** 配置面真实取值（workbuddy-file.yml）直写夹具 */
    private FileProperties fileProperties() {
        FileProperties fixture = new FileProperties();
        fixture.setMaxSize(104857600L);
        fixture.setEnabledFormats(List.of("PDF", "DOC", "DOCX", "XLS", "XLSX", "PPT", "PPTX"));
        fixture.setTempFileThreshold(10485760L);
        return fixture;
    }

    /** PDFBox 现场生成合法 PDF */
    private byte[] minimalPdf() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /**
     * 上传校验通过：落库并返回 fileId。
     *
     * <p>守护 {@code upload} 的成功路径：校验通过才写对象存储。
     */
    @Test
    void uploadShouldStoreAndReturnFileId() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "a.pdf", "application/pdf", minimalPdf());
        when(fileStorage.putFile(file)).thenReturn("F-1");

        String fileId = new FileServiceImpl(fileStorage, fileProperties()).upload(file);

        assertEquals("F-1", fileId);
        verify(fileStorage).putFile(file);
    }

    /**
     * 上传校验失败：抛 40422（原因入 detail），不落库。
     *
     * <p>守护 {@code upload} 的失败路径：任何校验原因都归一个错误码、细节进消息。
     */
    @Test
    void uploadRejectedShouldThrowAndNotStore() {
        MockMultipartFile file = new MockMultipartFile("file", "a.exe", "application/pdf", new byte[] {1});

        WorkbuddyException e = assertThrows(WorkbuddyException.class,
                () -> new FileServiceImpl(fileStorage, fileProperties()).upload(file));

        assertEquals(40422, e.getCode());
        assertTrue(e.getMessage().contains("格式不在允许范围"));
        verify(fileStorage, never()).putFile(file);
    }

    /**
     * 守护 {@code upload} 的空文件分支：0 字节按损坏拒绝。
     */
    @Test
    void uploadEmptyFileShouldReject() {
        MockMultipartFile file = new MockMultipartFile("file", "a.pdf", "application/pdf", new byte[0]);

        WorkbuddyException e = assertThrows(WorkbuddyException.class,
                () -> new FileServiceImpl(fileStorage, fileProperties()).upload(file));

        assertEquals(40422, e.getCode());
        assertTrue(e.getMessage().contains("文件已损坏"));
        verify(fileStorage, never()).putFile(file);
    }

    /**
     * 守护 {@code upload} 的大小上限分支：超限按上限拒绝。
     */
    @Test
    void uploadOversizedFileShouldReject() throws Exception {
        FileProperties props = fileProperties();
        props.setMaxSize(8);
        MockMultipartFile file = new MockMultipartFile("file", "a.pdf", "application/pdf", minimalPdf());

        WorkbuddyException e = assertThrows(WorkbuddyException.class,
                () -> new FileServiceImpl(fileStorage, props).upload(file));

        assertEquals(40422, e.getCode());
        assertTrue(e.getMessage().contains("文件超过大小上限"));
        verify(fileStorage, never()).putFile(file);
    }

    /**
     * 守护 {@code upload} 的魔数分支：后缀合法但内容不符按格式拒绝。
     */
    @Test
    void uploadContentMismatchingExtensionShouldReject() {
        MockMultipartFile file = new MockMultipartFile("file", "a.pdf", "application/pdf", "plain text".getBytes());

        WorkbuddyException e = assertThrows(WorkbuddyException.class,
                () -> new FileServiceImpl(fileStorage, fileProperties()).upload(file));

        assertEquals(40422, e.getCode());
        assertTrue(e.getMessage().contains("格式不在允许范围"));
        verify(fileStorage, never()).putFile(file);
    }

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
        metadata.setSha256("sha-abc");
        when(fileStorage.metadata("f1")).thenReturn(metadata);
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        when(fileStorage.open("f1")).thenReturn(new ByteArrayInputStream(content));
        MockHttpServletResponse response = new MockHttpServletResponse();

        new FileServiceImpl(fileStorage, fileProperties()).download("f1", response);

        assertTrue(response.getHeader("Content-Disposition").startsWith("attachment"), 
                response.getHeader("Content-Disposition"));
        assertEquals("application/octet-stream", response.getContentType());
        assertEquals("\"sha-abc\"", response.getHeader(HttpHeaders.ETAG));
        assertEquals("private, no-cache", response.getHeader(HttpHeaders.CACHE_CONTROL));
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

        new FileServiceImpl(fileStorage, fileProperties()).download("f2", response);

        assertNull(response.getHeader("Content-Length"));
        assertNull(response.getHeader(HttpHeaders.ETAG));
    }
}
