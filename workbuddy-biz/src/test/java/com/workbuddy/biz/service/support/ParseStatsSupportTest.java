package com.workbuddy.biz.service.support;

import com.workbuddy.common.domain.input.FileReference;
import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseResult;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.domain.parse.QualityInfo;
import com.workbuddy.common.dto.response.lineage.LineageParseStatsVO;
import com.workbuddy.common.enums.parse.ElementType;
import com.workbuddy.biz.service.support.stats.ParseStatsSupport;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 解析统计与结论单测。
 *
 * <p>守护 {@code stats} 的口径（正文 = 段落 + 列表 + 图注、页眉页脚合计、页数回退、失败单元范围）
 * 与 {@code summary} 的陈述规则：口径求和写错、页数回退判错都会让执行树显示假数据。
 */
class ParseStatsSupportTest {

    /**
     * 正文口径含列表与图注：三类合计而不是只数段落。
     *
     * <p>守护 {@code stats} 的正文求和：把加号写成减号会让正文数偏小。
     */
    @Test
    void statsShouldSumBodyListAndCaption() {
        ParseSource source = new ParseSource();
        source.setElements(List.of(element(ElementType.PARAGRAPH), element(ElementType.LIST),
                element(ElementType.FIGURE_CAPTION), element(ElementType.HEADER),
                element(ElementType.TABLE)));

        LineageParseStatsVO stats = ParseStatsSupport.stats(null, null, result(source));

        assertEquals(5, stats.getElementCount());
        assertEquals(3, stats.getBodyCount());
        assertEquals(1, stats.getHeaderFooterCount());
        assertEquals(1, stats.getTableCount());
    }

    /**
     * 页数：判定单元数优先；缺失时取文件引用页数，页数为 0 视为缺失。
     *
     * <p>守护 {@code resolvePageCount} 的回退与边界：0 页当下发会让页面显示「第 0 页」。
     */
    @Test
    void statsShouldResolvePageCountWithFallback() {
        ParseSource withUnits = new ParseSource();
        withUnits.setUnitCount(2);
        assertEquals(2, ParseStatsSupport.stats(null, null, result(withUnits)).getPageCount());

        ParseSource noUnits = new ParseSource();
        ParseResult withFile = result(noUnits);
        FileReference file = new FileReference();
        file.setPageCount(3);
        withFile.setFile(file);
        assertEquals(3, ParseStatsSupport.stats(null, null, withFile).getPageCount());

        ParseResult zeroPages = result(new ParseSource());
        FileReference zeroFile = new FileReference();
        zeroFile.setPageCount(0);
        zeroPageResult(zeroPages, zeroFile);
        assertNull(ParseStatsSupport.stats(null, null, zeroPages).getPageCount());

        // 文件引用缺失：页数为空
        assertNull(ParseStatsSupport.stats(null, null, result(new ParseSource())).getPageCount());
    }

    /**
     * 失败单元：去重升序后取始末单元号；无失败单元时三列为空。
     *
     * <p>守护 {@code stats} 的失败单元口径。
     */
    @Test
    void statsShouldCarryFailedUnitRange() {
        QualityInfo quality = new QualityInfo();
        quality.setFailedPages(List.of(5, 3, 5));
        ParseResult result = result(new ParseSource());
        result.setQuality(quality);

        LineageParseStatsVO stats = ParseStatsSupport.stats(null, null, result);

        assertEquals(2, stats.getFailedUnitCount());
        assertEquals(3, stats.getFailedFrom());
        assertEquals(5, stats.getFailedTo());
        assertNull(ParseStatsSupport.stats(null, null, result(new ParseSource())).getFailedUnitCount());
    }

    /**
     * 结论：统计缺失不陈述；无失败单元说「无异常」；有失败单元点名页码范围；失败态回退环节文案。
     *
     * <p>守护 {@code summary}/{@code parseSummaryBody}：空统计仍陈述或漏报失败单元即失败。
     */
    @Test
    void summaryShouldStateEachShape() {
        assertNull(ParseStatsSupport.summary(null, null, "SUCCESS"));

        LineageParseStatsVO healthy = new LineageParseStatsVO();
        assertEquals("无异常", ParseStatsSupport.summary(null, healthy, "SUCCESS"));

        LineageParseStatsVO failed = new LineageParseStatsVO();
        failed.setFailedUnitCount(2);
        failed.setFailedFrom(3);
        failed.setFailedTo(5);
        assertEquals("2 单元未解析出内容（第 3–5）", ParseStatsSupport.summary(null, failed, "PARTIAL_SUCCESS"));

        assertEquals("解析器超时", ParseStatsSupport.summary("解析器超时", null, "FAILED"));
        assertEquals("解析失败", ParseStatsSupport.summary(" ", null, "FAILED"));
        assertNull(ParseStatsSupport.summary(null, healthy, "RUNNING"));
    }

    /** 解析结果夹具（单来源） */
    private ParseResult result(ParseSource source) {
        ParseResult result = new ParseResult();
        result.setSources(new ArrayList<>(List.of(source)));
        return result;
    }

    /** 0 页文件引用的结果夹具（避免与主流程夹具混用） */
    private void zeroPageResult(ParseResult result, FileReference file) {
        result.setFile(file);
    }

    /** 解析元素夹具 */
    private ParseElement element(ElementType type) {
        return ParseElement.of("e-" + type.name(), type);
    }
}
