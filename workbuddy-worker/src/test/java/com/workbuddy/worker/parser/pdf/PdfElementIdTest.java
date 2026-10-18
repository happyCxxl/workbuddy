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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PDF 元素 ID 口径：`类型前缀 + 页码 + 页内序号 + 位置`，表格单元格挂在表 ID 下。
 * 样例文件见 samples/pdf-parse-12。
 */
class PdfElementIdTest {

    private final PdfBoxDocumentParser parser =
            new PdfBoxDocumentParser(new ProjectionPageLayoutAnalyzer());

    /** 同页两张表：单元格行列范围重合，ID 必须靠表归属区分 */
    @Test
    void samePageTwoTablesShouldNotShareElementIds() throws Exception {
        ParseSource source = parser.parse(context(buildTwoRuledTables()));

        List<ParseElement> tables = tablesOf(source);
        assertEquals(2, tables.size(), () -> "elements=" + source.getElements());
        List<String> ids = source.getElements().stream().map(ParseElement::getId).toList();
        assertEquals(ids.size(), new HashSet<>(ids).size(), () -> "ids=" + ids);

        List<String> cellIds = new ArrayList<>();
        for (ParseElement table : tables) {
            for (ParseElement cell : table.getCells()) {
                assertEquals(table.getId() + "_c" + cell.getRow() + "_" + cell.getCol(), cell.getId());
                cellIds.add(cell.getId());
            }
        }
        assertEquals(cellIds.size(), new HashSet<>(cellIds).size(), () -> "cellIds=" + cellIds);
    }

    /** ID = 前缀 + 页码（1）+ 页内序号（产出顺序）+ 位置（bbox 左上角整 pt） */
    @Test
    void elementIdShouldCarryPageSequenceAndPosition() throws Exception {
        ParseSource source = parser.parse(context(buildTwoRuledTables()));

        List<ParseElement> elements = source.getElements();
        assertEquals(3, elements.size(), () -> "elements=" + elements);
        assertEquals(ElementType.TABLE.name(), elements.get(0).getType());
        assertEquals(ElementType.TABLE.name(), elements.get(1).getType());
        assertEquals(ElementType.PARAGRAPH.name(), elements.get(2).getType());
        for (int i = 0; i < elements.size(); i++) {
            ParseElement element = elements.get(i);
            assertTrue(element.getId().matches("[a-z]{1,2}1_" + i + "_\\d+_\\d+"),
                    () -> "id=" + element.getId());
            assertTrue(element.getId().endsWith("_" + Math.round(element.getBbox().getX())
                    + "_" + Math.round(element.getBbox().getY())), () -> "id=" + element.getId());
        }
    }

    /** 同一输入两次解析得到同一套 ID（可复算） */
    @Test
    void elementIdsShouldBeRecomputable() throws Exception {
        byte[] data = buildTwoRuledTables();

        assertEquals(fingerprint(parser.parse(context(data))), fingerprint(parser.parse(context(data))));
    }

    /** 多页：ID 带各自页码，页内序号按页重新起算，跨页不重号 */
    @Test
    void elementIdShouldCarryPageNoAcrossPages() throws Exception {
        ParseSource source = parser.parse(context(buildTwoTablePages()));

        List<ParseElement> elements = source.getElements();
        assertEquals(6, elements.size(), () -> "elements=" + elements);
        List<String> ids = elements.stream().map(ParseElement::getId).toList();
        assertEquals(ids.size(), new HashSet<>(ids).size(), () -> "ids=" + ids);
        for (int i = 0; i < 3; i++) {
            assertTrue(ids.get(i).matches("[a-z]{1,2}1_" + i + "_\\d+_\\d+"),
                    () -> "ids=" + ids);
            assertTrue(ids.get(i + 3).matches("[a-z]{1,2}2_" + i + "_\\d+_\\d+"),
                    () -> "ids=" + ids);
        }
    }

    /** 元素指纹：类型 + ID + 文本（含单元格），用于两次解析结果对照 */
    private List<String> fingerprint(ParseSource source) {
        List<String> result = new ArrayList<>();
        for (ParseElement element : source.getElements()) {
            result.add(element.getType() + "|" + element.getId() + "|" + element.getText());
            if (element.getCells() != null) {
                for (ParseElement cell : element.getCells()) {
                    result.add("CELL|" + cell.getId() + "|" + cell.getText());
                }
            }
        }
        return result;
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
        fileRef.setFileName("two-tables.pdf");
        fileRef.setMimeType("application/pdf");
        context.setFileRef(fileRef);
        context.setInputStream(new ByteArrayInputStream(data));
        context.setProperties(new ParseProperties());
        return context;
    }

    /** 造一页：两张带框线表（上表 3×3、下表 2×2）+ 表后一段正文 */
    private byte[] buildTwoRuledTables() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            addTablePage(doc);
            return save(doc);
        }
    }

    /** 造两页同样的「两张表 + 一段正文」 */
    private byte[] buildTwoTablePages() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            addTablePage(doc);
            addTablePage(doc);
            return save(doc);
        }
    }

    /** 向文档追加一页：两张带框线表 + 表后一段正文 */
    private void addTablePage(PDDocument doc) throws Exception {
        PDPage page = new PDPage(new PDRectangle(595, 842));
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.setLineWidth(0.8f);
            cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
            drawTable(cs, new float[] {72, 172, 272, 372}, new float[] {700, 670, 640, 610},
                    new String[][] {{"Item", "Qty", "Price"}, {"A1", "2", "30"}, {"B2", "3", "40"}});
            drawTable(cs, new float[] {72, 172, 272}, new float[] {500, 470, 440},
                    new String[][] {{"Item", "Qty"}, {"C3", "5"}});
            cs.beginText();
            cs.newLineAtOffset(72, 400);
            cs.showText("Totals are indicative only");
            cs.endText();
        }
    }

    private byte[] save(PDDocument doc) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.save(out);
        return out.toByteArray();
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
}
