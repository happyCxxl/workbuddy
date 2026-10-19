package com.workbuddy.common.enums.input;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 文件格式目录单测。
 *
 * <p>守护 {@code ofMimeType} 的媒体类型解析：格式目录是解析器分发与入口校验的依据。
 */
class FileFormatTest {

    /**
     * 每个格式都能按声明的媒体类型解析回来。
     *
     * <p>守护 {@code ofMimeType}：媒体类型改错即失败（解析器分发不到）。
     */
    @Test
    void ofMimeTypeShouldResolveEveryFormat() {
        for (FileFormat format : FileFormat.values()) {
            assertEquals(format, FileFormat.ofMimeType(format.getMimeType()),
                    () -> format + " 按媒体类型解析失败");
            assertFalse(format.getMimeType().isBlank(), () -> format + " 媒体类型为空");
        }
    }

    /**
     * 未登记的媒体类型返回 null（入口校验据此拒绝）。
     *
     * <p>守护 {@code ofMimeType} 的未命中口径。
     */
    @Test
    void ofMimeTypeShouldReturnNullForUnknown() {
        assertNull(FileFormat.ofMimeType("application/no-such"));
        assertNull(FileFormat.ofMimeType(null));
    }

    /**
     * PDF 与 Office 的媒体类型是解析分发的锚点，不允许漂移。
     *
     * <p>守护 {@code FileFormat} 的关键锚点码值：常量重命名或类型改错即失败（既存入口与解析器失配）。
     */
    @Test
    void anchorMimeTypesShouldStayStable() {
        assertEquals(FileFormat.PDF, FileFormat.ofMimeType("application/pdf"));
        assertEquals(FileFormat.DOCX,
                FileFormat.ofMimeType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
        assertEquals(FileFormat.XLSX,
                FileFormat.ofMimeType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    }
}
