package com.workbuddy.common.enums.parse;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 解析质量告警码单测。
 *
 * <p>守护告警码词汇表的完整性：告警码随质量信息落库并驱动前端展示与降级判定，
 * 每类信号处置器声明一个码，缺码即该信号无处上报。
 */
class QualityWarningCodeTest {

    /**
     * 版面判定类告警码齐备（空白页、扫描件、矢量页、乱码、CID 缺映射）。
     *
     * <p>守护 {@code QualityWarningCode} 的版面信号词汇：删掉任一即失败（对应降级处置器无码可报）。
     */
    @Test
    void layoutSignalCodesShouldExist() {
        assertNotNull(QualityWarningCode.BLANK_PAGE);
        assertNotNull(QualityWarningCode.SCANNED_PAGE);
        assertNotNull(QualityWarningCode.VECTOR_PAGE_SUSPECTED);
        assertNotNull(QualityWarningCode.GARBLED_PAGE);
        assertNotNull(QualityWarningCode.CID_MAPPING_FAILED_PAGE);
    }

    /**
     * 图片与表格规则类告警码齐备。
     *
     * <p>守护 {@code QualityWarningCode} 的内容信号词汇：删掉任一即失败（图片/表格降级无处上报）。
     */
    @Test
    void contentSignalCodesShouldExist() {
        assertNotNull(QualityWarningCode.IMAGE_TEXT_UNRECOGNIZED);
        assertNotNull(QualityWarningCode.IMAGE_PAGE_SUSPECTED);
        assertNotNull(QualityWarningCode.TABLE_RULE_FALLBACK);
        assertNotNull(QualityWarningCode.LAYOUT_RULE_FALLBACK);
    }

    /**
     * 结构与质量汇总类告警码齐备。
     *
     * <p>守护 {@code QualityWarningCode} 的汇总词汇：标题候选、疑似续表、溯源缺失、噪声页、类型未映射、坏页占比。
     */
    @Test
    void aggregateSignalCodesShouldExist() {
        assertNotNull(QualityWarningCode.TITLE_CANDIDATE);
        assertNotNull(QualityWarningCode.SUSPECTED_CONTINUATION);
        assertNotNull(QualityWarningCode.PROVENANCE_MISSING);
        assertNotNull(QualityWarningCode.NOISE_PAGE);
        assertNotNull(QualityWarningCode.ELEMENT_TYPE_UNMAPPED);
        assertNotNull(QualityWarningCode.QUALITY_BAD_PAGE_RATIO);
    }
}
