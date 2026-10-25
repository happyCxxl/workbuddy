package com.workbuddy.worker.parser.pdf;

import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.enums.parse.ElementType;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 段落边界回归：编号条款按标记断段，普通续行仍归上一段。
 *
 * <p>行距故意小于聚合阈值，避免测试只验证“行距大”这一条路径。
 */
class PdfParagraphBoundaryTest {

    private final PdfParserTestFixture parser = new PdfParserTestFixture();

    /**
     * 守护 {@code numberedClausesShouldSplitEvenWithTightSpacing}：5/6/1/2.1 应各成一段。
     */
    @Test
    void numberedClausesShouldSplitEvenWithTightSpacing() throws Exception {
        ParseSource source = parser.parse(buildClausesPdf(false));

        List<String> paragraphs = paragraphTexts(source);

        assertEquals(4, paragraphs.size(), () -> "paragraphs=" + paragraphs);
        assertTrue(paragraphs.get(0).startsWith("5."), () -> "paragraphs=" + paragraphs);
        assertTrue(paragraphs.get(1).startsWith("6."), () -> "paragraphs=" + paragraphs);
        assertTrue(paragraphs.get(2).startsWith("1."), () -> "paragraphs=" + paragraphs);
        assertTrue(paragraphs.get(3).startsWith("2.1"), () -> "paragraphs=" + paragraphs);
    }

    /**
     * 守护 {@code continuationLineShouldStayInItsClause}：没有编号的续行不能断开。
     */
    @Test
    void continuationLineShouldStayInItsClause() throws Exception {
        ParseSource source = parser.parse(buildClausesPdf(true));

        List<String> paragraphs = paragraphTexts(source);

        assertEquals(2, paragraphs.size(), () -> "paragraphs=" + paragraphs);
        assertTrue(paragraphs.get(0).contains("continuation of clause five"),
                () -> "paragraphs=" + paragraphs);
        assertTrue(paragraphs.get(1).startsWith("6."), () -> "paragraphs=" + paragraphs);

        List<ParseElement> elements = source.getElements().stream()
                .filter(element -> ElementType.PARAGRAPH.name().equals(element.getType()))
                .toList();
        assertEquals(2, elements.get(0).getLineBoxes().size(),
                () -> "lineBoxes=" + elements.get(0).getLineBoxes());
    }

    private List<String> paragraphTexts(ParseSource source) {
        return source.getElements().stream()
                .filter(element -> ElementType.PARAGRAPH.name().equals(element.getType()))
                .map(ParseElement::getText)
                .toList();
    }

    /** 造一页编号条款；withContinuation=true 时在 5/6 之间插入一条无编号续行 */
    private byte[] buildClausesPdf(boolean withContinuation) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(595, 842));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                float y = 700;
                text(cs, y, "5. Payment term is thirty days");
                y -= 10;
                if (withContinuation) {
                    text(cs, y, "continuation of clause five");
                    y -= 10;
                }
                text(cs, y, "6. Consortium bids are not accepted");
                if (!withContinuation) {
                    y -= 10;
                    text(cs, y, "1. First qualification requirement");
                    y -= 10;
                    text(cs, y, "2.1 SME policy applies");
                }
            }
            return PdfParserTestFixture.save(doc);
        }
    }

    /** 写一行 12pt 文本 */
    private static void text(PDPageContentStream cs, float y, String value) throws IOException {
        cs.beginText();
        cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        cs.newLineAtOffset(72, y);
        cs.showText(value);
        cs.endText();
    }
}
