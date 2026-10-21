package com.workbuddy.worker.parser.pdf;

import com.workbuddy.common.domain.input.FileReference;
import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.enums.parse.ElementType;
import com.workbuddy.worker.parser.ParseContext;
import com.workbuddy.worker.parser.ParseProperties;
import com.workbuddy.worker.parser.pdf.layout.ProjectionPageLayoutAnalyzer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import com.workbuddy.worker.testkit.PropertiesFixtures;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * PDF 元素溯源：表与单元格按页内表序号定位，段落回指本页提取字符流的区间。
 * 样例文件见 samples/pdf-parse-12。
 */
class PdfProvenanceTest {

    private final PdfBoxDocumentParser parser =
            new PdfBoxDocumentParser(new ProjectionPageLayoutAnalyzer());

    /** 同页两张表的表序号与单元格路径各自独立（{@code tableAndCellProvenanceShouldCarryPageTableIndex}）。 */
    @Test
    void tableAndCellProvenanceShouldCarryPageTableIndex() throws Exception {
        ParseSource source = parser.parse(context(buildTwoRuledTables()));

        List<ParseElement> tables = tablesOf(source);
        assertEquals(2, tables.size(), () -> "elements=" + source.getElements());
        assertEquals("pdf#page(1)/table[0]", path(tables.get(0)));
        assertEquals("pdf#page(1)/table[1]", path(tables.get(1)));
        for (ParseElement table : tables) {
            String prefix = path(table) + "/cell[";
            for (ParseElement cell : table.getCells()) {
                assertEquals(prefix + cell.getRow() + "," + cell.getCol() + "]", path(cell));
            }
        }
        assertEquals(9, tables.get(0).getCells().size());
        assertEquals(4, tables.get(1).getCells().size());
        assertEquals("F-1", tables.get(0).getProvenance().getFile());
    }

    /** 段落区间：按页内阅读顺序累计行内字符数，行间不计分隔符（{@code paragraphProvenanceShouldCarryCharRange}）。 */
    @Test
    void paragraphProvenanceShouldCarryCharRange() throws Exception {
        ParseSource source = parser.parse(context(buildTwoParagraphPage()));

        List<String> paths = source.getElements().stream()
                .filter(element -> ElementType.PARAGRAPH.name().equals(element.getType()))
                .map(PdfProvenanceTest::path).toList();
        // 「First paragraph line」20 字 → [0..19]；「Second paragraph line」21 字 → [20..40]
        assertEquals(List.of("pdf#page(1)/chars[0..19]", "pdf#page(1)/chars[20..40]"), paths);
    }

    private static String path(ParseElement element) {
        assertNotNull(element.getProvenance(), () -> "element=" + element);
        return element.getProvenance().getPath();
    }

    private List<ParseElement> tablesOf(ParseSource source) {
        return source.getElements().stream()
                .filter(element -> ElementType.TABLE.name().equals(element.getType()))
                .toList();
    }

    private ParseContext context(byte[] data) {
        ParseContext context = new ParseContext();
        FileReference fileRef = new FileReference();
        fileRef.setFileId("F-1");
        fileRef.setFileName("provenance.pdf");
        fileRef.setMimeType("application/pdf");
        context.setFileRef(fileRef);
        context.setInputStream(new ByteArrayInputStream(data));
        context.setProperties(PropertiesFixtures.parse());
        return context;
    }

    /** 造一页两段单行正文：段间行距足够大，各自成段 */
    private byte[] buildTwoParagraphPage() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(595, 842));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                cs.beginText();
                cs.newLineAtOffset(72, 700);
                cs.showText("First paragraph line");
                cs.endText();
                cs.beginText();
                cs.newLineAtOffset(72, 600);
                cs.showText("Second paragraph line");
                cs.endText();
            }
            return save(doc);
        }
    }

    /** 造一页两张带框线表（上表 3×3、下表 2×2） */
    private byte[] buildTwoRuledTables() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(595, 842));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setLineWidth(0.8f);
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                drawTable(cs, new float[] {72, 172, 272, 372}, new float[] {700, 670, 640, 610},
                        new String[][] {{"Item", "Qty", "Price"}, {"A1", "2", "30"}, {"B2", "3", "40"}});
                drawTable(cs, new float[] {72, 172, 272}, new float[] {500, 470, 440},
                        new String[][] {{"Item", "Qty"}, {"C3", "5"}});
            }
            return save(doc);
        }
    }

    /** 画一张线框表：列线 × 行线成网格，文本写在格内上部 */
    private void drawTable(PDPageContentStream cs, float[] columns, float[] rows, String[][] texts)
            throws Exception {
        for (float x : columns) {
            drawLine(cs, x, rows[rows.length - 1], x, rows[0]);
        }
        for (float y : rows) {
            drawLine(cs, columns[0], y, columns[columns.length - 1], y);
        }
        for (int r = 0; r < texts.length; r++) {
            for (int c = 0; c < texts[r].length; c++) {
                cs.beginText();
                cs.newLineAtOffset(columns[c] + 6, rows[r] - 8);
                cs.showText(texts[r][c]);
                cs.endText();
            }
        }
    }

    private void drawLine(PDPageContentStream cs, float x1, float y1, float x2, float y2) throws Exception {
        cs.moveTo(x1, y1);
        cs.lineTo(x2, y2);
        cs.stroke();
    }

    private byte[] save(PDDocument doc) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.save(out);
        return out.toByteArray();
    }
}
