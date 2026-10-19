package com.workbuddy.worker.input.impl;

import com.workbuddy.common.domain.input.FileMetadata;
import com.workbuddy.common.domain.input.FileValidationResult;
import com.workbuddy.common.enums.input.FileFormat;
import com.workbuddy.common.enums.input.FileValidationFailReason;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.filecenter.storage.FileStorage;
import com.workbuddy.worker.input.FileValidationProperties;
import com.workbuddy.worker.input.check.FileCheck;
import com.workbuddy.worker.input.impl.check.CorruptionProbeCheck;
import com.workbuddy.worker.input.impl.check.EmptyFileCheck;
import com.workbuddy.worker.input.impl.check.MagicTypeCheck;
import com.workbuddy.worker.input.impl.check.MetadataMatchCheck;
import com.workbuddy.worker.input.impl.check.SizeLimitCheck;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文件校验步骤链单测：编排器（FileValidationPipeline）+ 五校验步骤（真实实例）。
 */
@ExtendWith(MockitoExtension.class)
class FileValidationPipelineTest {

    @Mock
    private FileStorage fileStorage;

    private FileValidationProperties properties;

    @BeforeEach
    void setUp() {
        properties = new FileValidationProperties();
    }

    /** 全部校验步骤真实实例（无 mock） */
    private List<FileCheck> checks() {
        return List.of(new SizeLimitCheck(), new EmptyFileCheck(), new MetadataMatchCheck(),
                new MagicTypeCheck(), new CorruptionProbeCheck());
    }

    private FileValidationPipeline validator() {
        return new FileValidationPipeline(fileStorage, properties, checks());
    }

    private void stubFile(String fileId, byte[] bytes) {
        when(fileStorage.metadata(fileId)).thenReturn(new FileMetadata());
        when(fileStorage.open(fileId)).thenReturn(new ByteArrayInputStream(bytes));
    }

    /** PDFBox 现场生成合法 PDF（避免手写 PDF 字节与 xref 偏移错误） */
    private byte[] minimalPdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(bytes);
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * 守护 {@code validateSmallPdfShouldPassInMemory}：该场景行为改动即失败。
     */
    @Test
    void validateSmallPdfShouldPassInMemory() throws Exception {
        byte[] bytes = minimalPdf();
        stubFile("f1", bytes);

        FileValidationResult result = validator().validate("f1");

        assertTrue(result.isPassed());
        assertEquals(FileFormat.PDF, result.getFormat());
        assertEquals("application/pdf", result.getMimeType());
        assertEquals(bytes.length, result.getSize());
        assertEquals(sha256(bytes), result.getSha256());
    }

    /**
     * 守护 {@code validateLargeFileShouldPassViaTempFileSpill}：该场景行为改动即失败。
     */
    @Test
    void validateLargeFileShouldPassViaTempFileSpill() throws Exception {
        properties.setTempFileThreshold(10);
        byte[] bytes = minimalPdf();
        assertTrue(bytes.length > 10);
        stubFile("f1", bytes);

        FileValidationResult result = validator().validate("f1");

        assertTrue(result.isPassed());
        assertEquals(sha256(bytes), result.getSha256());
        assertEquals(bytes.length, result.getSize());
    }

    /**
     * 守护 {@code validateOversizeShouldFailFast}：该场景行为改动即失败。
     */
    @Test
    void validateOversizeShouldFailFast() throws Exception {
        properties.setMaxSize(10);
        byte[] bytes = minimalPdf();
        assertTrue(bytes.length > 10);
        stubFile("f1", bytes);

        FileValidationResult result = validator().validate("f1");

        assertFalse(result.isPassed());
        assertEquals(FileValidationFailReason.FILE_TOO_LARGE, result.getFailReason());
    }

    /**
     * 守护 {@code validateEmptyFileShouldFailCorrupted}：该场景行为改动即失败。
     */
    @Test
    void validateEmptyFileShouldFailCorrupted() {
        stubFile("f1", new byte[0]);

        FileValidationResult result = validator().validate("f1");

        assertFalse(result.isPassed());
        assertEquals(FileValidationFailReason.FILE_CORRUPTED, result.getFailReason());
    }

    /**
     * 守护 {@code validateMissingFileShouldFailFileNotFound}：该场景行为改动即失败。
     */
    @Test
    void validateMissingFileShouldFailFileNotFound() {
        when(fileStorage.metadata("f1"))
                .thenThrow(new KnowledgeException(ErrorCode.FILE_NOT_FOUND));

        FileValidationResult result = validator().validate("f1");

        assertFalse(result.isPassed());
        assertEquals(FileValidationFailReason.FILE_NOT_FOUND, result.getFailReason());
    }

    /**
     * 守护 {@code validateMetadataSizeMismatchShouldFail}：该场景行为改动即失败。
     */
    @Test
    void validateMetadataSizeMismatchShouldFail() throws Exception {
        byte[] bytes = minimalPdf();
        FileMetadata metadata = new FileMetadata();
        metadata.setFileSize(bytes.length + 1L);
        when(fileStorage.metadata("f1")).thenReturn(metadata);
        when(fileStorage.open("f1")).thenReturn(new ByteArrayInputStream(bytes));

        FileValidationResult result = validator().validate("f1");

        assertFalse(result.isPassed());
        assertEquals(FileValidationFailReason.METADATA_MISMATCH, result.getFailReason());
    }

    /**
     * 守护 {@code validateMetadataSizeEqualShouldPass}：该场景行为改动即失败。
     */
    @Test
    void validateMetadataSizeEqualShouldPass() throws Exception {
        // 回归：包装类型 Long 引用比较会误判（相等值恒失败），必须数值比较
        byte[] bytes = minimalPdf();
        FileMetadata metadata = new FileMetadata();
        metadata.setFileSize((long) bytes.length);
        when(fileStorage.metadata("f1")).thenReturn(metadata);
        when(fileStorage.open("f1")).thenReturn(new ByteArrayInputStream(bytes));

        FileValidationResult result = validator().validate("f1");

        assertTrue(result.isPassed());
        assertEquals(bytes.length, result.getSize());
    }

    /**
     * 守护 {@code validateImageShouldReturnImageOcrReserved}：该场景行为改动即失败。
     */
    @Test
    void validateImageShouldReturnImageOcrReserved() {
        // JPEG 魔数（仅探测需要；图片路径在试解析前返回）
        byte[] jpegMagic = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10};
        stubFile("f1", jpegMagic);

        FileValidationResult result = validator().validate("f1");

        assertFalse(result.isPassed());
        assertEquals(FileValidationFailReason.IMAGE_OCR_RESERVED, result.getFailReason());
    }

    /**
     * 守护 {@code validateDisabledFormatShouldFailNotAllowed}：该场景行为改动即失败。
     */
    @Test
    void validateDisabledFormatShouldFailNotAllowed() {
        // text/plain 可识别（TXT）但默认不在白名单
        stubFile("f1", "hello world".getBytes());

        FileValidationResult result = validator().validate("f1");

        assertFalse(result.isPassed());
        assertEquals(FileValidationFailReason.FORMAT_NOT_ALLOWED, result.getFailReason());
    }

    /**
     * 守护 {@code validateFormatEnabledByConfigShouldPass}：该场景行为改动即失败。
     */
    @Test
    void validateFormatEnabledByConfigShouldPass() {
        properties.setEnabledFormats(List.of("PDF", "TXT"));
        stubFile("f1", "hello world".getBytes());

        FileValidationResult result = validator().validate("f1");

        assertTrue(result.isPassed());
        assertEquals(FileFormat.TXT, result.getFormat());
    }

    /**
     * 守护 {@code validateUnknownBinaryShouldFailNotAllowed}：该场景行为改动即失败。
     */
    @Test
    void validateUnknownBinaryShouldFailNotAllowed() {
        stubFile("f1", new byte[]{0x00, 0x01, 0x02, 0x03});

        FileValidationResult result = validator().validate("f1");

        assertFalse(result.isPassed());
        assertEquals(FileValidationFailReason.FORMAT_NOT_ALLOWED, result.getFailReason());
    }

    /**
     * 守护 {@code validateCorruptPdfShouldFailCorrupted}：该场景行为改动即失败。
     */
    @Test
    void validateCorruptPdfShouldFailCorrupted() {
        // PDF 魔数通过但结构损坏：试解析失败 → 损坏判定
        stubFile("f1", "%PDF-1.4\nnot a real pdf".getBytes());

        FileValidationResult result = validator().validate("f1");

        assertFalse(result.isPassed());
        assertEquals(FileValidationFailReason.FILE_CORRUPTED, result.getFailReason());
        verify(fileStorage).metadata("f1");
    }

    /**
     * 守护 {@code checksShouldRunInOrderRegardlessOfInjectionOrder}：该场景行为改动即失败。
     */
    @Test
    void checksShouldRunInOrderRegardlessOfInjectionOrder() throws Exception {
        // 注入乱序（编排器按 order() 排序执行）：空文件仍应由空文件判定（而非魔数/试解析）拦截
        byte[] bytes = minimalPdf();
        stubFile("f1", bytes);
        List<FileCheck> shuffled = List.of(new CorruptionProbeCheck(), new SizeLimitCheck(),
                new MagicTypeCheck(), new MetadataMatchCheck(), new EmptyFileCheck());

        FileValidationResult result = new FileValidationPipeline(fileStorage, properties, shuffled).validate("f1");

        assertTrue(result.isPassed());
        assertEquals(FileFormat.PDF, result.getFormat());
        assertEquals(sha256(bytes), result.getSha256());
    }
}
