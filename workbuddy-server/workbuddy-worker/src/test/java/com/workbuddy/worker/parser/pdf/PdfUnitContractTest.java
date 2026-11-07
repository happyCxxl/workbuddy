package com.workbuddy.worker.parser.pdf;

import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.domain.parse.ParseUnitResult;
import com.workbuddy.common.enums.parse.UnitType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单元契约：PDF 产出按物理页组织的单元集合（行摘要 + 单元指标 + 页尺寸），
 * 元素只存一份、按页归属单元，文档级汇总与单元保持一致。
 */
class PdfUnitContractTest {

    private final PdfParserTestFixture parser = new PdfParserTestFixture();
    private final PdfLayoutPageFixture pages = new PdfLayoutPageFixture();

    /** 单元集合按页组织：序号、类型、指标、尺寸、行摘要齐全（{@code pdfShouldProducePageUnits}）。 */
    @Test
    void pdfShouldProducePageUnits() throws Exception {
        ParseSource source = parser.parse(parser.context(pages.buildPdf()));

        assertEquals(UnitType.PAGE, source.getUnitType());
        assertEquals("pdfbox-3.0.4", source.getProvider());
        List<ParseUnitResult> units = source.getUnits();
        assertEquals(2, units.size());
        for (int index = 0; index < units.size(); index++) {
            ParseUnitResult unit = units.get(index);
            int pageNo = index + 1;
            assertEquals(pageNo, unit.getUnitIndex());
            assertEquals(UnitType.PAGE, unit.getType());
            assertNotNull(unit.getMetric());
            assertEquals(pageNo, unit.getMetric().getPage());
            assertNotNull(unit.getDimension());
            assertEquals(pageNo, unit.getDimension().getPage());
            assertFalse(unit.getLines().isEmpty(), () -> "unit lines=" + unit.getLines());
        }
    }

    /** 元素只存一份且归属单元：每个元素页码落在单元序号范围内，单元数与汇总单元数一致（{@code elementsShouldBelongToUnits}）。 */
    @Test
    void elementsShouldBelongToUnits() throws Exception {
        ParseSource source = parser.parse(parser.context(pages.buildPdf()));

        List<ParseElement> elements = source.getElements();
        assertEquals(2, source.getUnitCount());
        assertFalse(elements.isEmpty());
        for (ParseElement element : elements) {
            int page = element.getPage();
            assertTrue(page >= 1 && page <= source.getUnitCount(), () -> "element page=" + page);
            assertEquals(page, source.getUnits().get(page - 1).getUnitIndex());
        }
        assertEquals(source.getUnitCount(), source.getPageMetrics().size());
        assertEquals(source.getUnitCount(), source.getPageDimensions().size());
    }
}
