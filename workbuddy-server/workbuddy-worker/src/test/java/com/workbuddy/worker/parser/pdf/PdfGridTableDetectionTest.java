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
 * 线框表格识别回归：逐格边线/白缝表应合并成表；表单标签与普通段落不能被误判为表。
 */
class PdfGridTableDetectionTest {

    private final PdfParserTestFixture parser = new PdfParserTestFixture();

    /**
     * 守护 {@code cellBorderedTableShouldBeDetected}：逐格边线的 4×5 表应识别为一张完整表。
     */
    @Test
    void cellBorderedTableShouldBeDetected() throws Exception {
        ParseSource source = parser.parse(buildCellTable(4, 5, false));

        List<ParseElement> tables = source.getElements().stream()
                .filter(e -> ElementType.TABLE.name().equals(e.getType()))
                .toList();
        assertEquals(1, tables.size(), () -> "tables=" + tables);
        ParseElement table = tables.getFirst();
        assertEquals(4, table.getRows());
        assertEquals(5, table.getCols());
        assertEquals(20, table.getCells().size());
        assertTrue(table.getCells().stream()
                        .allMatch(cell -> cell.getText() != null && !cell.getText().isBlank()),
                () -> "empty cells: " + table.getCells());
    }

    /**
     * 守护 {@code labelFormShouldNotBecomeTable}：3×3 的「名称：/地址：」表单不能产出 TABLE。
     */
    @Test
    void labelFormShouldNotBecomeTable() throws Exception {
        ParseSource source = parser.parse(buildFormTable());

        assertEquals(0, source.getElements().stream()
                        .filter(e -> ElementType.TABLE.name().equals(e.getType())).count(),
                () -> "form became table: " + source.getElements());
        assertTrue(source.getElements().stream()
                        .filter(e -> ElementType.PARAGRAPH.name().equals(e.getType()))
                        .anyMatch(e -> e.getText() != null && e.getText().contains("Name:")),
                () -> "form text lost: " + source.getElements());
    }

    /**
     * 守护 {@code plainParagraphWithWideGapShouldNotBecomeTable}：无表格线的普通段落不能产出 TABLE。
     */
    @Test
    void plainParagraphWithWideGapShouldNotBecomeTable() throws Exception {
        ParseSource source = parser.parse(buildPlainParagraphPage());

        assertEquals(0, source.getElements().stream()
                        .filter(e -> ElementType.TABLE.name().equals(e.getType())).count(),
                () -> "paragraph became table: " + source.getElements());
        assertTrue(source.getElements().stream()
                        .filter(e -> ElementType.PARAGRAPH.name().equals(e.getType()))
                        .anyMatch(e -> e.getText() != null && e.getText().contains("plain paragraph")),
                () -> "paragraph text lost: " + source.getElements());
    }

    /**
     * 守护 {@code pageFrameWithSectionRulesShouldNotBecomeTable}：整页双框 + 整宽分节横线（外框延展到页外）
     * 不能产出 TABLE——框线与分节线不构成表格行列边界，正文按段落输出。
     */
    @Test
    void pageFrameWithSectionRulesShouldNotBecomeTable() throws Exception {
        ParseSource source = parser.parse(buildPageFrame());

        assertEquals(0, source.getElements().stream()
                        .filter(e -> ElementType.TABLE.name().equals(e.getType())).count(),
                () -> "page frame became table: " + source.getElements());
        assertTrue(source.getElements().stream()
                        .filter(e -> ElementType.PARAGRAPH.name().equals(e.getType()))
                        .anyMatch(e -> e.getText() != null && e.getText().contains("EDUCATION")),
                () -> "frame text lost: " + source.getElements());
    }

    /** 造逐格填充表（单元格各自是独立矩形，模拟白缝/底色表） */
    private byte[] buildCellTable(int rows, int cols, boolean labels) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(595, 842));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                float x = 72;
                float y = 620;
                float width = 450;
                float height = 30;
                float cellWidth = width / cols;
                for (int r = 0; r < rows; r++) {
                    for (int c = 0; c < cols; c++) {
                        cs.setNonStrokingColor(new Color(230, 230, 230));
                        cs.addRect(x + c * cellWidth + 1, y - (r + 1) * height + 1,
                                cellWidth - 2, height - 2);
                        cs.fill();
                        String text = labels ? label(r, c) : "R" + r + "C" + c;
                        text(cs, x + c * cellWidth + 4, y - r * height - 20, 9, text);
                    }
                }
            }
            return PdfParserTestFixture.save(doc);
        }
    }

    /** 造 3×3 标签表单 */
    private byte[] buildFormTable() throws IOException {
        return buildCellTable(3, 3, true);
    }

    /** 造无表格线、仅普通段落页 */
    private byte[] buildPlainParagraphPage() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(595, 842));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                text(cs, 72, 700, 10, "This is a plain paragraph with a wide gap");
                text(cs, 72, 680, 10, "and another line without any table border");
                text(cs, 72, 660, 10, "the layout is just running text");
            }
            return PdfParserTestFixture.save(doc);
        }
    }

    /**
     * 造「整页双框 + 分节横线」页：外框按两页高绘制（页外部分渲染时不可见），
     * 内框与分节横线是装饰，正文为单栏段落。
     */
    private byte[] buildPageFrame() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(595, 842));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setLineWidth(0.8f);
                // 外框：上下各延展一页高（被页面裁剪，渲染时只看到页内部分）
                PdfParserTestFixture.drawLine(cs, 0, -824, 0, 842);
                PdfParserTestFixture.drawLine(cs, 593, -824, 593, 842);
                PdfParserTestFixture.drawLine(cs, 0, -824, 593, -824);
                PdfParserTestFixture.drawLine(cs, 0, 842, 593, 842);
                // 内框 + 整宽分节横线
                PdfParserTestFixture.drawLine(cs, 8.6f, 8.6f, 8.6f, 833);
                PdfParserTestFixture.drawLine(cs, 584.7f, 8.6f, 584.7f, 833);
                PdfParserTestFixture.drawLine(cs, 8.6f, 744, 584.7f, 744);
                PdfParserTestFixture.drawLine(cs, 8.6f, 611, 584.7f, 611);
                PdfParserTestFixture.drawLine(cs, 8.6f, 297, 584.7f, 297);
                PdfParserTestFixture.drawLine(cs, 8.6f, 52, 584.7f, 52);
                // 正文：联系方式 + 分节标题 + 段落
                text(cs, 24, 794, 10, "Name Phone Email");
                text(cs, 8.6f, 712, 10, "EDUCATION");
                text(cs, 8.6f, 690, 9, "Bachelor of Finance, university, 2018-09 to 2022-06");
                text(cs, 8.6f, 674, 9, "Self-taught Java and Spring during university years");
                text(cs, 8.6f, 579, 10, "SKILLS");
                text(cs, 25.8f, 557, 9, "Familiar with Java collections, concurrency and Spring Boot");
                text(cs, 25.8f, 541, 9, "Familiar with Redis, Caffeine and multi-level cache design");
                text(cs, 8.6f, 265, 10, "EXPERIENCE");
                text(cs, 15.7f, 243, 9, "Led inventory management system development from scratch");
            }
            return PdfParserTestFixture.save(doc);
        }
    }

    /** 表单标签 */
    private static String label(int row, int col) {
        String[][] labels = {
                {"Name:", "Address:", "Contact:"},
                {"Person:", "Phone:", "Postcode:"},
                {"Bank:", "Account:", "Note:"}
        };
        return labels[row][col];
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
