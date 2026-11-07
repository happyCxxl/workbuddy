package com.workbuddy.common.domain.parse;

import com.workbuddy.common.domain.parse.capability.CapabilityUsage;
import com.workbuddy.common.domain.parse.signal.PageMetric;
import com.workbuddy.common.domain.parse.signal.ParseFact;
import com.workbuddy.common.enums.parse.ElementType;
import com.workbuddy.common.enums.parse.UnitType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单元集合派生文档级汇总视图单测。
 *
 * <p>守护 {@code fromDocument}：元素按单元序展平、指标与尺寸按单元序汇总、事实与能力沿用路级结果；
 * 空指标/空尺寸的单元不产生占位条目。
 */
class ParseSourceDocumentTest {

    /** 两单元派生：元素全程展平、指标尺寸顺序一致、路级字段沿用。 */
    @Test
    void fromDocumentShouldFlattenUnitsInOrder() {
        ParseDocument document = document();

        ParseSource source = ParseSource.fromDocument(document);

        assertEquals("native", source.getSource());
        assertEquals("pdfbox-3.0.4", source.getProvider());
        assertTrue(source.getCandidateOrder());
        assertEquals(2, source.getUnitCount());
        assertEquals(List.of("p1", "p2", "p3"), source.getElements().stream().map(ParseElement::getId).toList());
        assertEquals(List.of(1, 2), source.getPageMetrics().stream().map(PageMetric::getPage).toList());
        assertEquals(List.of(1, 2), source.getPageDimensions().stream().map(PageDimension::getPage).toList());
        assertEquals(1, source.getFacts().size());
        assertEquals(1, source.getCapabilities().size());
    }

    /** 单元指标/尺寸为空时跳过，不产生占位条目。 */
    @Test
    void fromDocumentShouldSkipMissingMetricAndDimension() {
        ParseUnitResult unit = new ParseUnitResult();
        unit.setUnitIndex(1);
        unit.setType(UnitType.BLOCK_STREAM);
        ParseDocument document = new ParseDocument();
        document.setProvider("poi-5.4.0");
        document.setUnitType(UnitType.BLOCK_STREAM);
        document.setUnits(List.of(unit));

        ParseSource source = ParseSource.fromDocument(document);

        assertEquals(1, source.getUnitCount());
        assertTrue(source.getPageMetrics().isEmpty());
        assertTrue(source.getPageDimensions().isEmpty());
    }

    /** 单元与元素夹具：两页、三元素、各带指标与尺寸。 */
    private ParseDocument document() {
        ParseUnitResult first = new ParseUnitResult();
        first.setUnitIndex(1);
        first.setType(UnitType.PAGE);
        first.setMetric(metric(1));
        first.setDimension(new PageDimension(1, 595.0, 842.0));
        first.setLayoutHeight(842.0);
        first.setLines(List.of(new UnitLine("header", 72, 42, 100, 12)));
        first.getElements().add(element("p1"));
        first.getElements().add(element("p2"));

        ParseUnitResult second = new ParseUnitResult();
        second.setUnitIndex(2);
        second.setType(UnitType.PAGE);
        second.setMetric(metric(2));
        second.setDimension(new PageDimension(2, 595.0, 842.0));
        second.getElements().add(element("p3"));

        ParseFact fact = new ParseFact();
        fact.setRegion("page(1)");
        CapabilityUsage usage = new CapabilityUsage();

        ParseDocument document = new ParseDocument();
        document.setProvider("pdfbox-3.0.4");
        document.setUnitType(UnitType.PAGE);
        document.setUnits(List.of(first, second));
        document.setFacts(List.of(fact));
        document.setCapabilities(List.of(usage));
        return document;
    }

    private PageMetric metric(int page) {
        PageMetric metric = new PageMetric();
        metric.setPage(page);
        metric.setCharCount(10);
        return metric;
    }

    private ParseElement element(String id) {
        return ParseElement.of(id, ElementType.PARAGRAPH);
    }
}
