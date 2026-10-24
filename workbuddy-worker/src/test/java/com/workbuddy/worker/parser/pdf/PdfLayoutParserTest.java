package com.workbuddy.worker.parser.pdf;

import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.enums.parse.ElementType;
import com.workbuddy.worker.parser.ParseContext;
import com.workbuddy.worker.parser.pdf.detect.HeaderFooterDetector;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PDF 版面解析单测：段落聚合、页眉页脚识别、页底数字判定、目录行与双栏阅读顺序。
 */
class PdfLayoutParserTest {

    private final PdfParserTestFixture parser = new PdfParserTestFixture();
    private final PdfLayoutPageFixture pages = new PdfLayoutPageFixture();

    /**
     * 守护 {@code pdfShouldParseParagraphsAndHeader}：该场景行为改动即失败。
     */
    @Test
    void pdfShouldParseParagraphsAndHeader() throws Exception {
        ParseContext context = parser.context(pages.buildPdf());
        ParseSource source = parser.parse(context);

        assertEquals(2, source.getUnitCount());
        assertEquals(2, context.getFileRef().getPageCount());
        assertEquals(2, source.getPageMetrics().size());
        assertTrue(source.getPageMetrics().getFirst().getCharCount() > 0);
        assertTrue(source.getPageMetrics().getFirst().getGarbledRatio() < 0.2);

        // HEADER：两页同位置重复文本，每页各一条
        List<ParseElement> headers = source.getElements().stream()
                .filter(e -> ElementType.HEADER.name().equals(e.getType()))
                .toList();
        assertEquals(2, headers.size());
        assertEquals("XX Project Header", headers.getFirst().getText());

        // 段落：正文（页眉除外）
        List<ParseElement> paragraphs = source.getElements().stream()
                .filter(e -> ElementType.PARAGRAPH.name().equals(e.getType()))
                .toList();
        assertTrue(paragraphs.size() >= 2);
        assertTrue(paragraphs.stream().anyMatch(p -> p.getText().contains("Chapter one body")));
        // 行间拼接：同一段落内的相邻行之间补空格
        assertTrue(paragraphs.stream()
                .anyMatch(p -> p.getText().contains("line 0 Chapter one body continues")));
        // 溯源与坐标
        assertNotNull(paragraphs.getFirst().getProvenance());
        assertTrue(paragraphs.getFirst().getProvenance().getPath().startsWith("pdf#page"));
        assertNotNull(paragraphs.getFirst().getBbox());
    }

    /**
     * 守护 {@code repeatedFooterAndPageNumberShouldEmitFooterElements}：该场景行为改动即失败。
     */
    @Test
    void repeatedFooterAndPageNumberShouldEmitFooterElements() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            for (int page = 0; page < 2; page++) {
                PDPage pdfPage = new PDPage(
                        new PDRectangle(595, 842));
                doc.addPage(pdfPage);
                try (PDPageContentStream cs =
                             new PDPageContentStream(doc, pdfPage)) {
                    cs.setFont(new PDType1Font(
                            Standard14Fonts.FontName.HELVETICA), 10);
                    // 正文
                    cs.beginText();
                    cs.newLineAtOffset(72, 400);
                    cs.showText("Body text of page " + page);
                    cs.endText();
                    // 页脚：两页同位置同文本（页底）
                    cs.beginText();
                    cs.newLineAtOffset(250, 30);
                    cs.showText("XXProjectFooter");
                    cs.endText();
                    // 页码：每页不同（页码模式；与页脚文本不同行，避免行聚合合并）
                    cs.beginText();
                    cs.newLineAtOffset(500, 18);
                    cs.showText(String.valueOf(page + 1));
                    cs.endText();
                }
            }
            ParseSource source = parser.parse(PdfParserTestFixture.save(doc));

            List<ParseElement> footers = source.getElements().stream()
                    .filter(e -> ElementType.FOOTER.name().equals(e.getType()))
                    .toList();
            // 页脚文本每页一条 + 页码每页一条（两页页码同址，跨页判定通过）
            assertEquals(4, footers.size(), () -> "footers=" + footers + " all=" + source.getElements());
            assertTrue(footers.stream().anyMatch(f -> "XXProjectFooter".equals(f.getText())));
            assertTrue(footers.stream().anyMatch(f -> "1".equals(f.getText())), () -> "footers=" + footers);
            assertTrue(footers.stream().anyMatch(f -> "2".equals(f.getText())), () -> "footers=" + footers);
            // 页脚不进正文流
            List<ParseElement> paragraphs = source.getElements().stream()
                    .filter(e -> ElementType.PARAGRAPH.name().equals(e.getType()))
                    .toList();
            assertTrue(paragraphs.stream().noneMatch(p -> p.getText().contains("XXProjectFooter")));
        }
    }

    /**
     * 守护 {@code footerWithTrailingPageNumberShouldStripSuffixAndDetect}：该场景行为改动即失败。
     */
    @Test
    void footerWithTrailingPageNumberShouldStripSuffixAndDetect() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            for (int page = 0; page < 2; page++) {
                PDPage pdfPage = new PDPage(
                        new PDRectangle(595, 842));
                doc.addPage(pdfPage);
                try (PDPageContentStream cs =
                             new PDPageContentStream(doc, pdfPage)) {
                    cs.setFont(new PDType1Font(
                            Standard14Fonts.FontName.HELVETICA), 10);
                    // 正文
                    cs.beginText();
                    cs.newLineAtOffset(72, 400);
                    cs.showText("Body text of page " + page);
                    cs.endText();
                    // 页脚与页码同行：行聚合后为 XXProjectFooter1 / XXProjectFooter2
                    cs.beginText();
                    cs.newLineAtOffset(250, 30);
                    cs.showText("XXProjectFooter");
                    cs.newLineAtOffset(150, 0);
                    cs.showText(String.valueOf(page + 1));
                    cs.endText();
                }
            }
            ParseSource source = parser.parse(PdfParserTestFixture.save(doc));

            List<ParseElement> footers = source.getElements().stream()
                    .filter(e -> ElementType.FOOTER.name().equals(e.getType()))
                    .toList();
            // 页脚与页码同行：每页各一条，文本取该页原行
            assertEquals(2, footers.size(), () -> "footers=" + footers);
            assertEquals("XXProjectFooter 1", footers.getFirst().getText());
            assertEquals("XXProjectFooter 2", footers.getLast().getText());
            // 页脚不进正文流
            List<ParseElement> paragraphs = source.getElements().stream()
                    .filter(e -> ElementType.PARAGRAPH.name().equals(e.getType()))
                    .toList();
            assertTrue(paragraphs.stream().noneMatch(p -> p.getText().contains("XXProjectFooter")));
        }
    }

    /**
     * 守护 {@code tocLineShouldMarkTocCandidate}：该场景行为改动即失败。
     */
    @Test
    void tocLineShouldMarkTocCandidate() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage pdfPage = new PDPage(
                    new PDRectangle(595, 842));
            doc.addPage(pdfPage);
            try (PDPageContentStream cs =
                         new PDPageContentStream(doc, pdfPage)) {
                cs.setFont(new PDType1Font(
                        Standard14Fonts.FontName.HELVETICA), 10);
                // 目录行：标题 + 点线引导符 + 行尾页码
                cs.beginText();
                cs.newLineAtOffset(72, 700);
                cs.showText("Chapter One .... 1");
                cs.endText();
                // 正文行：行尾无页码
                cs.beginText();
                cs.newLineAtOffset(72, 680);
                cs.showText("This is body paragraph text");
                cs.endText();
            }
            ParseSource source = parser.parse(PdfParserTestFixture.save(doc));

            assertTrue(source.getElements().stream()
                    .filter(e -> ElementType.PARAGRAPH.name().equals(e.getType()))
                    .anyMatch(e -> Boolean.TRUE.equals(e.getTocCandidate())
                            && e.getText().contains("Chapter One .... 1")));
        }
    }

    /**
     * 守护 {@code headerFooterKeyShouldNormalizeWhitespace}：该场景行为改动即失败。
     */
    @Test
    void headerFooterKeyShouldNormalizeWhitespace() {
        assertEquals("XX Project Header", HeaderFooterDetector.normalizeKey("  XX   Project \t Header "));
        assertEquals("知识库", HeaderFooterDetector.normalizeKey("\u200B知识库"));
        assertEquals("", HeaderFooterDetector.normalizeKey("   "));
    }

    /**
     * 守护 {@code bodyLineEqualToHeaderTextShouldStayInBody}：该场景行为改动即失败。
     */
    @Test
    void bodyLineEqualToHeaderTextShouldStayInBody() throws Exception {
        ParseSource source = parser.parse(pages.buildPages(List.of(List.of(), List.of()),
                "XX Project Header", "XX Project Header"));

        List<ParseElement> headers = source.getElements().stream()
                .filter(e -> ElementType.HEADER.name().equals(e.getType()))
                .toList();
        assertEquals(2, headers.size());
        // 页眉带之外的正文同文本行保留在正文流
        assertTrue(source.getElements().stream()
                        .filter(e -> ElementType.PARAGRAPH.name().equals(e.getType()))
                        .anyMatch(e -> "XX Project Header".equals(e.getText())),
                () -> "elements=" + source.getElements());
    }

    /**
     * 守护 {@code bottomNumberOnSinglePageShouldStayInBody}：该场景行为改动即失败。
     */
    @Test
    void bottomNumberOnSinglePageShouldStayInBody() throws Exception {
        ParseSource source = parser.parse(pages.buildPages(
                List.of(List.of(PdfLayoutPageFixture.bottomNumber("2024", 100))), null, "Body text"));

        assertEquals(0, source.getElements().stream()
                .filter(e -> ElementType.FOOTER.name().equals(e.getType())).count());
        assertTrue(source.getElements().stream()
                        .filter(e -> ElementType.PARAGRAPH.name().equals(e.getType()))
                        .anyMatch(e -> e.getText().contains("2024")),
                () -> "elements=" + source.getElements());
    }

    /**
     * 守护 {@code pageNumberShouldRequireSamePositionAcrossPages}：该场景行为改动即失败。
     */
    @Test
    void pageNumberShouldRequireSamePositionAcrossPages() throws Exception {
        ParseSource source = parser.parse(pages.buildPages(List.of(
                List.of(PdfLayoutPageFixture.bottomNumber("1", 290)),
                List.of(PdfLayoutPageFixture.bottomNumber("2", 290))), null, "Body text"));

        List<String> footers = source.getElements().stream()
                .filter(e -> ElementType.FOOTER.name().equals(e.getType()))
                .map(ParseElement::getText).toList();
        assertEquals(2, footers.size(), () -> "footers=" + footers);
        assertTrue(footers.contains("1"));
        assertTrue(footers.contains("2"));
    }

    /**
     * 守护 {@code pageNumberAtDifferentPositionsShouldStayInBody}：该场景行为改动即失败。
     */
    @Test
    void pageNumberAtDifferentPositionsShouldStayInBody() throws Exception {
        ParseSource source = parser.parse(pages.buildPages(List.of(
                List.of(PdfLayoutPageFixture.bottomNumber("1", 500)),
                List.of(PdfLayoutPageFixture.bottomNumber("2", 100))), null, "Body text"));

        assertEquals(0, source.getElements().stream()
                .filter(e -> ElementType.FOOTER.name().equals(e.getType())).count());
        List<String> paragraphs = source.getElements().stream()
                .filter(e -> ElementType.PARAGRAPH.name().equals(e.getType()))
                .map(ParseElement::getText).toList();
        assertTrue(paragraphs.contains("1"), () -> "paragraphs=" + paragraphs);
        assertTrue(paragraphs.contains("2"), () -> "paragraphs=" + paragraphs);
    }

    /**
     * 守护 {@code multipleNumbersOnOnePageShouldNotAbortOtherPages}：某页多条数字只排除该页，
     * 其他页的同址页码仍应识别。
     */
    @Test
    void multipleNumbersOnOnePageShouldNotAbortOtherPages() throws Exception {
        ParseSource source = parser.parse(pages.buildPages(List.of(
                List.of(PdfLayoutPageFixture.bottomNumber("10", 100),
                        PdfLayoutPageFixture.bottomNumber("20", 400)),
                List.of(PdfLayoutPageFixture.bottomNumber("2", 290)),
                List.of(PdfLayoutPageFixture.bottomNumber("3", 290))), null, "Body text"));

        List<String> footers = source.getElements().stream()
                .filter(e -> ElementType.FOOTER.name().equals(e.getType()))
                .map(ParseElement::getText).toList();
        assertEquals(2, footers.size(), () -> "footers=" + footers);
        assertTrue(footers.contains("2"));
        assertTrue(footers.contains("3"));
        assertTrue(source.getElements().stream()
                        .filter(e -> ElementType.PARAGRAPH.name().equals(e.getType()))
                        .anyMatch(e -> e.getText().contains("10") && e.getText().contains("20")),
                () -> "page1 numbers should stay in body: " + source.getElements());
    }

    /**
     * 守护 {@code multipleBottomNumbersOnSamePageShouldNotBePageNumbers}：该场景行为改动即失败。
     */
    @Test
    void multipleBottomNumbersOnSamePageShouldNotBePageNumbers() throws Exception {
        ParseSource source = parser.parse(pages.buildPages(List.of(
                List.of(PdfLayoutPageFixture.bottomNumber("10", 100),
                        PdfLayoutPageFixture.bottomNumber("20", 400))), null, "Body text"));

        assertEquals(0, source.getElements().stream()
                .filter(e -> ElementType.FOOTER.name().equals(e.getType())).count());
        assertTrue(source.getElements().stream()
                        .filter(e -> ElementType.PARAGRAPH.name().equals(e.getType()))
                        .anyMatch(e -> e.getText().contains("10") && e.getText().contains("20")),
                () -> "elements=" + source.getElements());
    }

    /**
     * 守护 {@code twoColumnPageShouldReadByColumnAndNotBecomeTable}：该场景行为改动即失败。
     */
    @Test
    void twoColumnPageShouldReadByColumnAndNotBecomeTable() throws Exception {
        ParseSource source = parser.parse(pages.buildTwoColumnPage());

        List<String> paragraphs = source.getElements().stream()
                .filter(e -> ElementType.PARAGRAPH.name().equals(e.getType()))
                .map(ParseElement::getText).toList();
        assertEquals(List.of(
                        "Left column first line here Left column second line here Left column third line here",
                        "Right column first line here Right column second line here Right column third line here"),
                paragraphs, () -> "elements=" + source.getElements());
        assertTrue(source.getElements().stream()
                .noneMatch(e -> ElementType.TABLE.name().equals(e.getType())));
    }

    /**
     * 守护 {@code spanningHeadingShouldSplitBandsInTwoColumnPage}：该场景行为改动即失败。
     */
    @Test
    void spanningHeadingShouldSplitBandsInTwoColumnPage() throws Exception {
        ParseSource source = parser.parse(pages.buildSpanningHeadingPage());

        List<String> paragraphs = source.getElements().stream()
                .filter(e -> ElementType.PARAGRAPH.name().equals(e.getType()))
                .map(ParseElement::getText).toList();
        assertEquals(List.of(
                        "Spanning Heading Number One Across Both Columns",
                        "Left column first line here Left column second line here Left column third line here",
                        "Right column first line here Right column second line here Right column third line here",
                        "Spanning Heading Number Two Across Both Columns",
                        "Left column fourth line here Left column fifth line here",
                        "Right column fourth line here Right column fifth line here"),
                paragraphs, () -> "elements=" + source.getElements());
    }
}
