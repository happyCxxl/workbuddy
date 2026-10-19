package com.workbuddy.common.enums.input;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 文件校验失败文案单测。
 *
 * <p>守护 {@code label} 的三条口径：空白返回 null、已知原因翻译中文（大小写/空白不敏感）、
 * 未知名原样返回——翻译错会让用户看到错误提示。
 */
class FileValidationFailLabelsTest {

    /**
     * 空白原因返回 null。
     *
     * <p>守护 {@code label} 的空入参兜底。
     */
    @Test
    void labelShouldReturnNullForBlank() {
        assertNull(FileValidationFailLabels.label(null));
        assertNull(FileValidationFailLabels.label(" "));
    }

    /**
     * 已知原因按枚举名翻译（大小写与首尾空白不敏感）。
     *
     * <p>守护 {@code label} 的解析：大小写敏感会漏翻历史日志。
     */
    @Test
    void labelShouldTranslateKnownReasonIgnoringCaseAndSpace() {
        assertEquals("文件超过大小上限",
                FileValidationFailLabels.label("FILE_TOO_LARGE"));
        assertEquals("文件超过大小上限",
                FileValidationFailLabels.label(" file_too_large "));
        assertEquals("文件已损坏",
                FileValidationFailLabels.label("FILE_CORRUPTED"));
    }

    /**
     * 未知名原样返回（便于排障）。
     *
     * <p>守护 {@code label} 的兜底：未知名不能返回 null 或空串。
     */
    @Test
    void labelShouldKeepUnknownReason() {
        assertEquals("SOMETHING_NEW", FileValidationFailLabels.label("SOMETHING_NEW"));
    }
}
