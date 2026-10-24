package com.workbuddy.worker.parser.pdf.assemble;

import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.signal.PageMetric;
import com.workbuddy.common.enums.parse.ElementType;
import com.workbuddy.worker.parser.pdf.model.PageContent;
import com.workbuddy.worker.parser.pdf.model.PageLine;
import com.workbuddy.worker.parser.pdf.model.PdfPageGeometry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 段落落地判空：空白文本不产 PARAGRAPH，正常文本保留。
 */
class PdfBodyAssemblerParagraphTest {

    /**
     * 守护 {@code blankParagraphShouldNotBeEmitted}：纯空白段落不产元素。
     */
    @Test
    void blankParagraphShouldNotBeEmitted() {
        List<ParseElement> elements = new ArrayList<>();
        PageLine blank = line("   ");

        PdfBodyAssembler.addParagraph(List.of(blank), elements, "F-1", page(List.of(blank)));

        assertEquals(0, elements.size(), () -> "elements=" + elements);
    }

    /**
     * 守护 {@code normalParagraphShouldBeEmitted}：正常文本仍产 PARAGRAPH。
     */
    @Test
    void normalParagraphShouldBeEmitted() {
        List<ParseElement> elements = new ArrayList<>();
        PageLine normal = line("Real paragraph text");

        PdfBodyAssembler.addParagraph(List.of(normal), elements, "F-1", page(List.of(normal)));

        assertEquals(1, elements.size(), () -> "elements=" + elements);
        assertEquals(ElementType.PARAGRAPH.name(), elements.getFirst().getType());
        assertEquals("Real paragraph text", elements.getFirst().getText());
    }

    /** 造一行 */
    private static PageLine line(String text) {
        return new PageLine(1, 72, 100, 200, 10, text, 10, "Helvetica", false,
                List.of(), List.of(), false);
    }

    /** 造单页内容 */
    private static PageContent page(List<PageLine> lines) {
        return new PageContent(1, 595, 842, new PdfPageGeometry(595, 842, 0), new PageMetric(),
                lines, List.of(), List.of());
    }
}
