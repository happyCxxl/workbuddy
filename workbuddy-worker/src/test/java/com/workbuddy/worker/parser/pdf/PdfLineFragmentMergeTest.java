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

import java.awt.Color;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 行片段合并回归：跨栏标题/页眉/页脚/页码应合并，真实双栏/窄栏/逻辑页眉块不能被误合并。
 */
class PdfLineFragmentMergeTest {

    private final PdfParserTestFixture parser = new PdfParserTestFixture();

    /**
     * 守护 {@code splitFragmentsShouldMerge}：标题/页眉/页脚/页码被切成相邻片段后应恢复为一条。
     */
    @Test
    void splitFragmentsShouldMerge() throws Exception {
        ParseSource source = parser.parse(buildSplitFragmentsPage());

        assertEquals(2, count(source, ElementType.HEADER), () -> "headers=" + source.getElements());
        assertEquals(2, count(source, ElementType.FOOTER), () -> "footers=" + source.getElements());
        List<String> paragraphs = texts(source, ElementType.PARAGRAPH);
        assertTrue(paragraphs.stream().anyMatch(text -> text.contains("PART ONE INVITATION")),
                () -> "title fragments not merged: " + paragraphs);
        assertTrue(source.getElements().stream()
                        .filter(e -> ElementType.FOOTER.name().equals(e.getType()))
                        .anyMatch(e -> e.getText() != null && e.getText().contains("101")),
                () -> "footer fragments not merged: " + source.getElements());
    }

    /**
     * 守护 {@code shadedTableShouldStayDetected}：底色/白缝表不能被行合并丢掉。
     */
    @Test
    void shadedTableShouldStayDetected() throws Exception {
        ParseSource source = parser.parse(buildShadedTablePage());
        assertEquals(2, count(source, ElementType.TABLE), () -> "tables=" + source.getElements());
    }

    /**
     * 守护 {@code narrowGutterTwoColumnsShouldStaySeparate}：窄栏距双栏正文不能被片段合并误合成一段。
     */
    @Test
    void narrowGutterTwoColumnsShouldStaySeparate() throws Exception {
        ParseSource source = parser.parse(buildNarrowTwoColumnPage());
        List<String> paragraphs = texts(source, ElementType.PARAGRAPH);
        int left = indexOf(paragraphs, "Left narrow first line");
        int right = indexOf(paragraphs, "Right narrow first line");
        assertTrue(left >= 0 && right >= 0 && left < right,
                () -> "narrow columns merged or reordered: " + paragraphs);
    }

    /**
     * 守护 {@code twoColumnWithTableShouldKeepColumnsAndTable}：真实双栏 + 左栏表格的列序与表都不能被误合并。
     */
    @Test
    void twoColumnWithTableShouldKeepColumnsAndTable() throws Exception {
        ParseSource source = parser.parse(buildTwoColumnWithTable());
        List<String> paragraphs = texts(source, ElementType.PARAGRAPH);
        int left = indexOf(paragraphs, "Left column first line here");
        int right = indexOf(paragraphs, "Right column first line here");
        assertTrue(left >= 0 && right >= 0 && left < right,
                () -> "column order broken: " + paragraphs);
        assertEquals(1, count(source, ElementType.TABLE), () -> "tables=" + source.getElements());
    }

    /** 造页：跨栏页眉/页脚/标题在表格栏沟处被切成片段，共两页。 */
    private byte[] buildSplitFragmentsPage() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int page = 0; page < 2; page++) {
                PDPage pdfPage = new PDPage(new PDRectangle(595, 842));
                doc.addPage(pdfPage);
                try (PDPageContentStream cs = new PDPageContentStream(doc, pdfPage)) {
                    text(cs, 200, 800, 9, "DOC");
                    text(cs, 218, 800, 9, "HEADER");
                    text(cs, 150, 720, 18, "PART ONE");
                    text(cs, 270, 720, 18, "INVITATION");
                    ruledTable(cs, 72, 650, 450, 25, 3, 4);
                    text(cs, 200, 30, 9, "- 101");
                    text(cs, 221, 30, 9, "-");
                }
            }
            return PdfParserTestFixture.save(doc);
        }
    }

    /** 造页：底色/白缝表，共两页。 */
    private byte[] buildShadedTablePage() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int page = 0; page < 2; page++) {
                PDPage pdfPage = new PDPage(new PDRectangle(595, 842));
                doc.addPage(pdfPage);
                try (PDPageContentStream cs = new PDPageContentStream(doc, pdfPage)) {
                    shadedTable(cs, 72, 650, 450, 25, 3, 4);
                }
            }
            return PdfParserTestFixture.save(doc);
        }
    }

    /** 造页：窄栏距双栏正文。 */
    private byte[] buildNarrowTwoColumnPage() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(595, 842));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                text(cs, 72, 700, 10, "Left narrow first line with more words");
                text(cs, 72, 688, 10, "Left narrow second line with more words");
                text(cs, 287, 700, 10, "Right narrow first line");
                text(cs, 287, 688, 10, "Right narrow second line");
            }
            return PdfParserTestFixture.save(doc);
        }
    }

    /** 造页：双栏正文 + 左栏表格。 */
    private byte[] buildTwoColumnWithTable() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(595, 842));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                text(cs, 72, 700, 10, "Left column first line here");
                text(cs, 72, 688, 10, "Left column second line here");
                text(cs, 330, 700, 10, "Right column first line here");
                text(cs, 330, 688, 10, "Right column second line here");
                ruledTable(cs, 72, 600, 198, 25, 2, 3);
            }
            return PdfParserTestFixture.save(doc);
        }
    }

    /** 画线框表 */
    private static void ruledTable(PDPageContentStream cs, float x, float y, float width,
                                   float rowHeight, int cols, int rows) throws IOException {
        float colWidth = width / cols;
        for (int c = 0; c <= cols; c++) {
            float cx = x + c * colWidth;
            PdfParserTestFixture.drawLine(cs, cx, y, cx, y - rows * rowHeight);
        }
        for (int r = 0; r <= rows; r++) {
            float ry = y - r * rowHeight;
            PdfParserTestFixture.drawLine(cs, x, ry, x + width, ry);
        }
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                text(cs, x + c * colWidth + 4, y - r * rowHeight - 15, 8, "C" + r + c);
            }
        }
    }

    /** 画底色/白缝表（无描边） */
    private static void shadedTable(PDPageContentStream cs, float x, float y, float width,
                                    float rowHeight, int cols, int rows) throws IOException {
        float colWidth = width / cols;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                cs.setNonStrokingColor(new Color(220, 220, 220));
                cs.addRect(x + c * colWidth + 1, y - (r + 1) * rowHeight + 1, colWidth - 2, rowHeight - 2);
                cs.fill();
            }
        }
        cs.setNonStrokingColor(Color.BLACK);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                text(cs, x + c * colWidth + 4, y - r * rowHeight - 15, 8, "S" + r + c);
            }
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

    /** 取某类型文本 */
    private static List<String> texts(ParseSource source, ElementType type) {
        return source.getElements().stream()
                .filter(e -> type.name().equals(e.getType()))
                .map(e -> e.getText() == null ? "" : e.getText())
                .toList();
    }

    /** 取某类型条数 */
    private static long count(ParseSource source, ElementType type) {
        return source.getElements().stream().filter(e -> type.name().equals(e.getType())).count();
    }

    /** 文本列表里包含 needle 的首个下标 */
    private static int indexOf(List<String> texts, String needle) {
        for (int i = 0; i < texts.size(); i++) {
            if (texts.get(i).contains(needle)) {
                return i;
            }
        }
        return -1;
    }
}
