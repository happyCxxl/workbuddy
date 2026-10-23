package com.workbuddy.filecenter.util;

import com.workbuddy.common.enums.input.FileFormat;
import com.workbuddy.common.enums.input.FileValidationFailReason;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文件工具原语单测：后缀 / 白名单 / 媒体类型判定 / 空文件 / 读流统计 / 试解析探测（静态方法直测）。
 */
class FileUtilTest {

    /**
     * 守护 {@code extensionOf}：后缀提取（保留原大小写），无点与空名返回 null。
     */
    @Test
    void extensionOfShouldExtractLowercasedSuffix() {
        assertEquals("PDF", FileUtil.extensionOf("a.PDF"));
        assertEquals("docx", FileUtil.extensionOf("b.docx"));
        assertNull(FileUtil.extensionOf("noext"));
        assertNull(FileUtil.extensionOf("trailing."));
        assertNull(FileUtil.extensionOf(""));
        assertNull(FileUtil.extensionOf(null));
    }

    /**
     * 守护 {@code whitelisted}：枚举名命中启用清单。
     */
    @Test
    void whitelistedShouldMatchByName() {
        assertTrue(FileUtil.whitelisted(FileFormat.PDF, List.of("PDF", "DOC")));
        assertFalse(FileUtil.whitelisted(FileFormat.TXT, List.of("PDF", "DOC")));
    }

    /**
     * 守护 {@code isProtected / isReservedImage / ofFormat}：媒体类型判定。
     */
    @Test
    void mediaTypeJudgementsShouldBeStable() {
        assertTrue(FileUtil.isProtected("application/x-tika-ooxml-protected"));
        assertFalse(FileUtil.isProtected("application/pdf"));
        assertTrue(FileUtil.isReservedImage("image/png"));
        assertFalse(FileUtil.isReservedImage("application/pdf"));
        assertEquals(FileFormat.PDF, FileUtil.ofFormat("application/pdf"));
        assertNull(FileUtil.ofFormat("application/unknown"));
        assertNull(FileUtil.ofFormat(null));
    }

    /**
     * 守护 {@code isEmpty}：空值非空判定，0 视为空。
     */
    @Test
    void isEmptyShouldTreatNullAsNotEmpty() {
        assertFalse(FileUtil.isEmpty(null));
        assertFalse(FileUtil.isEmpty(1L));
        assertTrue(FileUtil.isEmpty(0L));
    }

    /**
     * 守护 {@code count}：单遍统计，超上限即停（8KB 块读，累计值到块边界）。
     */
    @Test
    void countShouldStopAtLimit() throws Exception {
        byte[] bytes = "hello world".getBytes();
        assertEquals(11L, FileUtil.count(new ByteArrayInputStream(bytes), 100L));
        // 内容远超上限：首个 8KB 块读入后累计值即超上限，立即停止
        byte[] large = new byte[100 * 1024];
        assertEquals(8192L, FileUtil.count(new ByteArrayInputStream(large), 10L));
    }

    /**
     * 守护 {@code probe}：结构损坏按损坏失败。
     */
    @Test
    void probeShouldRejectCorruptContent() {
        FileValidationFailReason reason =
                FileUtil.probe(new ByteArrayInputStream("%PDF-1.4\nnot a real pdf".getBytes()));

        assertEquals(FileValidationFailReason.FILE_CORRUPTED, reason);
    }
}
