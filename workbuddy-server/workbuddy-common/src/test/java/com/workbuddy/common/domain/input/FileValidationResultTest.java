package com.workbuddy.common.domain.input;

import com.workbuddy.common.enums.input.FileFormat;
import com.workbuddy.common.enums.input.FileValidationFailReason;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文件校验结果单测。
 *
 * <p>守护两个工厂的字段口径：通过时带全建档三写所需字段且无失败原因；
 * 失败时只带失败原因、其余为空——多带字段会让失败记录被当成通过落库。
 */
class FileValidationResultTest {

    /**
     * 通过结果：passed=true，格式/类型/大小/指纹齐全，失败原因为空。
     *
     * <p>守护 {@code pass}：漏字段会让建档三写取到 null。
     */
    @Test
    void passShouldCarryAllBuildFields() {
        FileValidationResult result = FileValidationResult.pass(
                FileFormat.PDF, "application/pdf", 1024L, "sha-1");

        assertTrue(result.isPassed());
        assertEquals(FileFormat.PDF, result.getFormat());
        assertEquals("application/pdf", result.getMimeType());
        assertEquals(1024L, result.getSize());
        assertEquals("sha-1", result.getSha256());
        assertNull(result.getFailReason());
    }

    /**
     * 失败结果：passed=false，只带失败原因。
     *
     * <p>守护 {@code fail}：失败却带格式/指纹会让下游误建结果行。
     */
    @Test
    void failShouldCarryOnlyReason() {
        FileValidationResult result = FileValidationResult.fail(
                FileValidationFailReason.FORMAT_NOT_ALLOWED);

        assertFalse(result.isPassed());
        assertEquals(FileValidationFailReason.FORMAT_NOT_ALLOWED, result.getFailReason());
        assertNull(result.getFormat());
        assertNull(result.getMimeType());
        assertNull(result.getSize());
        assertNull(result.getSha256());
    }
}
