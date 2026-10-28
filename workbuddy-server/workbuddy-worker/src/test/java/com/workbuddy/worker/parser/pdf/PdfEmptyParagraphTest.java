package com.workbuddy.worker.parser.pdf;

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
 * 空白段落回归：纯空格行不能产出 PARAGRAPH，正常段落必须保留。
 */
class PdfEmptyParagraphTest {

    private final PdfParserTestFixture parser = new PdfParserTestFixture();

    /**
     * 守护 {@code blankLinesShouldNotBecomeParagraphs}：空格行不产空段落，正常段落保留。
     */
    @Test
    void blankLinesShouldNotBecomeParagraphs() throws Exception {
        ParseSource source = parser.parse(buildPdf());

        List<String> paragraphs = source.getElements().stream()
                .filter(e -> ElementType.PARAGRAPH.name().equals(e.getType()))
                .map(e -> e.getText() == null ? "" : e.getText())
                .toList();
        assertEquals(0, paragraphs.stream().filter(String::isBlank).count(),
                () -> "blank paragraphs=" + paragraphs);
        assertTrue(paragraphs.stream().anyMatch(text -> text.contains("Real paragraph text")),
                () -> "normal paragraph lost: " + paragraphs);
        assertTrue(paragraphs.stream().anyMatch(text -> text.contains("Second real paragraph")),
                () -> "normal paragraph lost: " + paragraphs);
    }

    /** 造页：正常段落 + 纯空格行 + 正常段落。 */
    private byte[] buildPdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(595, 842));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                text(cs, 72, 700, 10, "Real paragraph text");
                text(cs, 72, 680, 10, "          ");
                text(cs, 72, 660, 10, "Second real paragraph");
            }
            return PdfParserTestFixture.save(doc);
        }
    }

    /** 写一行文本 */
    private static void text(PDPageContentStream cs, float x, float y, float size, String text)
            throws IOException {
        cs.beginText();
        cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), size);
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.endText();
    }
}
