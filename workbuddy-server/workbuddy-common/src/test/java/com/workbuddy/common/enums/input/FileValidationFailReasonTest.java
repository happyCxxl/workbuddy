package com.workbuddy.common.enums.input;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 文件校验失败原因的单测。
 *
 * <p>守护 {@link FileValidationFailReason} 的两条契约（JAVA-DTO-011）：码值落库存 {@code name()}，
 * 展示名从枚举取；回读时大小写与首尾空白不敏感、未知码值原样返回。
 */
class FileValidationFailReasonTest {

    /**
     * 展示名与码值一一对应。
     *
     * <p>守护 {@link FileValidationFailReason#label()}：改码值不改展示名时用户提示会漂移。
     */
    @Test
    void labelShouldMatchEachReason() {
        assertEquals("文件不存在或不可读", FileValidationFailReason.FILE_NOT_FOUND.label());
        assertEquals("格式不在允许范围", FileValidationFailReason.FORMAT_NOT_ALLOWED.label());
        assertEquals("文件超过大小上限", FileValidationFailReason.FILE_TOO_LARGE.label());
        assertEquals("文件已损坏", FileValidationFailReason.FILE_CORRUPTED.label());
        assertEquals("文件已加密", FileValidationFailReason.FILE_ENCRYPTED.label());
        assertEquals("元数据与实际不符", FileValidationFailReason.METADATA_MISMATCH.label());
        assertEquals("图片类型暂不支持", FileValidationFailReason.IMAGE_OCR_RESERVED.label());
    }

    /**
     * 回读库中码值：空值不展示、大小写与首尾空白不敏感、未知码值原样返回。
     *
     * <p>守护 {@link FileValidationFailReason#labelOf(String)}（COMMON-TEST-11）：
     * 历史日志里的大小写混写不能漏翻，新增码值之前写入的未知值不能变成空白。
     */
    @Test
    void labelOfShouldParseStoredCode() {
        assertNull(FileValidationFailReason.labelOf(null));
        assertNull(FileValidationFailReason.labelOf(" "));
        assertEquals("文件超过大小上限", FileValidationFailReason.labelOf("FILE_TOO_LARGE"));
        assertEquals("文件超过大小上限", FileValidationFailReason.labelOf(" file_too_large "));
        assertEquals("文件已损坏", FileValidationFailReason.labelOf("FILE_CORRUPTED"));
        assertEquals("SOMETHING_NEW", FileValidationFailReason.labelOf("SOMETHING_NEW"));
    }
}
