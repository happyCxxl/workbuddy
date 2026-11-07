package com.workbuddy.common.domain.parse;

import com.workbuddy.common.domain.parse.signal.PageMetric;
import com.workbuddy.common.enums.parse.ParseSourceType;
import com.workbuddy.common.enums.parse.UnitType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 解析来源单测。
 *
 * <p>守护 {@code nativeSource}：路名固定为 native、提供方原样、带候选顺序标记；
 * 路名写错会让组装环节把原生解析当成 OCR 路。
 */
class ParseSourceTest {

    /**
     * 原生路：source= native、provider 原样、candidateOrder=true。
     *
     * <p>守护 {@code nativeSource}。
     */
    @Test
    void nativeSourceShouldCarryFixedRoute() {
        ParseSource source = ParseSource.nativeSource("pdfbox-3.0.4");

        assertEquals(ParseSourceType.NATIVE.value(), source.getSource());
        assertEquals("pdfbox-3.0.4", source.getProvider());
        assertTrue(source.getCandidateOrder());
        assertTrue(source.getElements().isEmpty());
    }

    /**
     * 单元汇总：单元总数为单元个数，指标与尺寸按单元序汇总，空指标/空尺寸不产生占位。
     *
     * <p>守护 {@code summarizeUnits}；下游判定按单元数算成功占比，多算一个空单元会压低成功率。
     */
    @Test
    void summarizeUnitsShouldRollUpUnitCountAndPageFacts() {
        ParseSource source = ParseSource.nativeSource("pdfbox-3.0.4");
        source.setUnitType(UnitType.PAGE);
        source.setUnits(new ArrayList<>(List.of(unit(1), unit(2), emptyUnit(3))));

        source.summarizeUnits();

        assertEquals(3, source.getUnitCount());
        assertEquals(List.of(1, 2), source.getPageMetrics().stream().map(PageMetric::getPage).toList());
        assertEquals(List.of(1, 2), source.getPageDimensions().stream().map(PageDimension::getPage).toList());
    }

    /** 重复汇总不应累加旧汇总值。 */
    @Test
    void summarizeUnitsShouldBeIdempotent() {
        ParseSource source = ParseSource.nativeSource("pdfbox-3.0.4");
        source.setUnits(new ArrayList<>(List.of(unit(1))));

        source.summarizeUnits();
        source.summarizeUnits();

        assertEquals(1, source.getUnitCount());
        assertEquals(1, source.getPageMetrics().size());
        assertEquals(1, source.getPageDimensions().size());
    }

    /** 带指标与尺寸的单元。 */
    private ParseUnitResult unit(int index) {
        ParseUnitResult unit = new ParseUnitResult();
        unit.setUnitIndex(index);
        unit.setType(UnitType.PAGE);
        PageMetric metric = new PageMetric();
        metric.setPage(index);
        unit.setMetric(metric);
        unit.setDimension(new PageDimension(index, 595.0, 842.0));
        return unit;
    }

    /** 只有序号的单元（Office 无物理尺寸时的形态）。 */
    private ParseUnitResult emptyUnit(int index) {
        ParseUnitResult unit = new ParseUnitResult();
        unit.setUnitIndex(index);
        unit.setType(UnitType.PAGE);
        return unit;
    }
}
