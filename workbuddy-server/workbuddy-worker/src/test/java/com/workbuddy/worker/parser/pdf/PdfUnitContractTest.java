package com.workbuddy.worker.parser.pdf;

import com.workbuddy.common.domain.parse.ParseDocument;
import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.domain.parse.ParseUnitResult;
import com.workbuddy.common.enums.parse.UnitType;
import com.workbuddy.worker.parser.ParseContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单元契约：PDF 产出按物理页组织的单元集合（元素 + 行摘要 + 单元指标 + 页尺寸），
 * 文档级汇总视图由单元派生且与直接解析结果逐元素一致。
 */
class PdfUnitContractTest {

    private final PdfParserTestFixture parser = new PdfParserTestFixture();
    private final PdfLayoutPageFixture pages = new PdfLayoutPageFixture();

    /** 单元集合按页组织：序号、类型、指标、尺寸、行摘要与元素页码一一对应（{@code pdfShouldProducePageUnits}）。 */
    @Test
    void pdfShouldProducePageUnits() throws Exception {
        ParseContext context = parser.context(pages.buildPdf());
        ParseDocument document = parser.parseDocument(context);

        assertEquals(UnitType.PAGE, document.getUnitType());
        assertEquals("pdfbox-3.0.4", document.getProvider());
        List<ParseUnitResult> units = document.getUnits();
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
            assertFalse(unit.getElements().isEmpty(), () -> "unit elements=" + unit.getElements());
            assertTrue(unit.getElements().stream()
                            .allMatch(element -> Integer.valueOf(pageNo).equals(element.getPage())),
                    () -> "unit elements page mismatch, page=" + pageNo);
        }
    }

    /** 汇总视图由单元派生：展平后的元素、指标、尺寸与直接解析结果一致（{@code sourceShouldDeriveFromUnits}）。 */
    @Test
    void sourceShouldDeriveFromUnits() throws Exception {
        byte[] data = pages.buildPdf();
        ParseSource direct = parser.parse(parser.context(data));
        ParseSource derived = ParseSource.fromDocument(parser.parseDocument(parser.context(data)));

        assertEquals(direct.getProvider(), derived.getProvider());
        assertEquals(direct.getUnitCount(), derived.getUnitCount());
        assertEquals(ids(direct.getElements()), ids(derived.getElements()));
        assertEquals(direct.getPageMetrics(), derived.getPageMetrics());
        assertEquals(direct.getPageDimensions(), derived.getPageDimensions());
        assertEquals(direct.getFacts(), derived.getFacts());
    }

    private List<String> ids(List<ParseElement> elements) {
        return elements.stream().map(ParseElement::getId).toList();
    }
}
