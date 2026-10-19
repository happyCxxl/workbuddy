package com.workbuddy.common.domain.input;

import com.workbuddy.common.enums.input.FileValidationFailReason;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验步骤结果单测。
 *
 * <p>守护 {@code pass}/{@code fail} 的两字段口径：通过不带失败原因、失败带原因；
 * 写错会让失败步骤被当成通过继续建档。
 */
class FileCheckResultTest {

    /**
     * 通过：passed=true 且无失败原因。
     *
     * <p>守护 {@code pass}：多带原因会让日志误导。
     */
    @Test
    void passShouldCarryNoReason() {
        FileCheckResult result = FileCheckResult.pass();

        assertTrue(result.passed());
        assertNull(result.failReason());
    }

    /**
     * 失败：passed=false 且带原因。
     *
     * <p>守护 {@code fail}：原因丢失会让失败日志无法翻译。
     */
    @Test
    void failShouldCarryReason() {
        FileCheckResult result = FileCheckResult.fail(FileValidationFailReason.FILE_CORRUPTED);

        assertFalse(result.passed());
        assertEquals(FileValidationFailReason.FILE_CORRUPTED, result.failReason());
    }
}
