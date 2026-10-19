package com.workbuddy.worker.parser.support;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 目录行级特征单测：点线引导符 + 行尾页码正例；正文反例。
 */
class TocLineFeatureTest {

    /**
     * 守护 {@code tocLinesShouldMatch}：该场景行为改动即失败。
     */
    @Test
    void tocLinesShouldMatch() {
        assertTrue(TocLineFeature.isTocLine("第一章 总则 ...... 1"));
        assertTrue(TocLineFeature.isTocLine("第一章 总则……12"));
        assertTrue(TocLineFeature.isTocLine("1.1 招标范围 . . . 3"));
        assertTrue(TocLineFeature.isTocLine("第二章 投标人须知    5"));
        assertTrue(TocLineFeature.isTocLine("附件一 资质要求 …… 123"));
    }

    /**
     * 守护 {@code bodyLinesShouldNotMatch}：该场景行为改动即失败。
     */
    @Test
    void bodyLinesShouldNotMatch() {
        assertFalse(TocLineFeature.isTocLine("第一章 总则（正文标题）"));
        assertFalse(TocLineFeature.isTocLine("投标保证金为人民币叁佰万元整。"));
        assertFalse(TocLineFeature.isTocLine(""));
        assertFalse(TocLineFeature.isTocLine("   "));
        // 行尾非页码
        assertFalse(TocLineFeature.isTocLine("评分标准 ...... 详见附件"));
        // 无引导符的短行（行尾有数字但中间无点线/长空白）
        assertFalse(TocLineFeature.isTocLine("工期30天"));
    }
}
