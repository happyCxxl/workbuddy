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
 * 目录段落粒度回归：每一条目录行应独立成段，不能被聚合成一整段。
 */
class PdfTocParagraphTest {

    private final PdfParserTestFixture parser = new PdfParserTestFixture();

    /**
     * 守护 {@code tocLinesShouldStaySeparate}：三行点线目录应产出三段，而不是一段。
     */
    @Test
    void tocLinesShouldStaySeparate() throws Exception {
        ParseSource source = parser.parse(buildTocPdf());

        List<ParseElement> tocParagraphs = source.getElements().stream()
                .filter(e -> ElementType.PARAGRAPH.name().equals(e.getType()))
                .filter(e -> Boolean.TRUE.equals(e.getTocCandidate()))
                .toList();

        assertEquals(3, tocParagraphs.size(), () -> "paragraphs=" + tocParagraphs);
        assertTrue(tocParagraphs.stream().anyMatch(e -> e.getText().contains("Chapter One")));
        assertTrue(tocParagraphs.stream().anyMatch(e -> e.getText().contains("Chapter Two")));
        assertTrue(tocParagraphs.stream().anyMatch(e -> e.getText().contains("Chapter Three")));
        assertTrue(tocParagraphs.stream().noneMatch(e -> e.getText().contains("Chapter Two")
                && e.getText().contains("Chapter Three")), () -> "still merged=" + tocParagraphs);
    }

    /** 造页：三行点线目录，行距小于普通段落的聚合阈值 */
    private byte[] buildTocPdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(595, 842));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                text(cs, 72, 700, 12, "Chapter One .... 1");
                text(cs, 72, 688, 12, "Chapter Two .... 3");
                text(cs, 72, 676, 12, "Chapter Three .... 5");
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
