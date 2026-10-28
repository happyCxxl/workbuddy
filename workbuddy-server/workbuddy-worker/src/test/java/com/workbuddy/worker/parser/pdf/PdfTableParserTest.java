package com.workbuddy.worker.parser.pdf;

import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.enums.parse.ElementType;
import com.workbuddy.worker.parser.pdf.assemble.RuledTableBuilder;
import com.workbuddy.worker.parser.pdf.assemble.TextTableBuilder;
import com.workbuddy.worker.parser.pdf.detect.TableCandidateDetector;
import com.workbuddy.worker.parser.pdf.model.Grid;
import com.workbuddy.worker.parser.pdf.model.Token;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PDF 表格解析单测：列对齐启发式、线网格与合并格、表头行判定、假表门限。
 */
class PdfTableParserTest {

    private final PdfParserTestFixture parser = new PdfParserTestFixture();
    private final PdfTablePageFixture pages = new PdfTablePageFixture();

    /**
     * 守护 {@code alignedColumnsShouldFormTable}：该场景行为改动即失败。
     */
    @Test
    void alignedColumnsShouldFormTable() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage pdfPage = new PDPage(new PDRectangle(595, 842));
            doc.addPage(pdfPage);
            try (PDPageContentStream cs = new PDPageContentStream(doc, pdfPage)) {
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                // 三行、三列，列距 > 20pt（宽列距信号）
                String[][] rows = {
                        {"Score", "Standard", "Points"},
                        {"A1", "Bidding price", "30"},
                        {"A2", "Tech plan", "40"}};
                float y = 700;
                for (String[] row : rows) {
                    cs.beginText();
                    cs.newLineAtOffset(60, y);
                    cs.showText(row[0]);
                    cs.newLineAtOffset(130, 0);
                    cs.showText(row[1]);
                    cs.newLineAtOffset(150, 0);
                    cs.showText(row[2]);
                    cs.endText();
                    y -= 24;
                }
            }
            ParseSource source = parser.parse(PdfParserTestFixture.save(doc));

            List<ParseElement> tables = source.getElements().stream()
                    .filter(e -> ElementType.TABLE.name().equals(e.getType()))
                    .toList();
            assertEquals(1, tables.size(), () -> "elements=" + source.getElements());
            assertEquals(3, tables.getFirst().getRows());
            assertEquals(3, tables.getFirst().getCols());
            assertEquals("Score", tables.getFirst().getCells().getFirst().getText());
            // 格内多词完整保留（词边界补空格）
            assertEquals("Bidding price", tables.getFirst().getCells().get(4).getText());
            assertTrue(tables.getFirst().getCells().getFirst().getIsHeader());
        }
    }

    /**
     * 守护 {@code borderedTableShouldUseRuleGridWithSpanAndSparseRow}：该场景行为改动即失败。
     */
    @Test
    void borderedTableShouldUseRuleGridWithSpanAndSparseRow() throws Exception {
        ParseSource source = parser.parse(pages.buildRuledTable());

        List<ParseElement> tables = source.getElements().stream()
                .filter(e -> ElementType.TABLE.name().equals(e.getType()))
                .toList();
        assertEquals(1, tables.size(), () -> "elements=" + source.getElements());
        ParseElement table = tables.getFirst();
        // 三行三列；首行横向合并成一格，第二行只填一格也留在同一张表内
        assertEquals(3, table.getRows());
        assertEquals(3, table.getCols());
        assertEquals(0, table.getHeaderRow());
        assertEquals(7, table.getCells().size());

        ParseElement merged = table.getCells().stream()
                .filter(c -> c.getRow() == 0 && c.getCol() == 0).findFirst().orElse(null);
        assertNotNull(merged);
        assertEquals(3, merged.getColSpan());
        assertEquals("Score sheet", merged.getText());
        assertTrue(merged.getIsHeader());

        assertEquals("A1", cellText(table, 1, 0));
        assertEquals("Bidding price", cellText(table, 1, 1));
        assertEquals("30", cellText(table, 2, 2));
    }

    /**
     * 守护 {@code ruledEmptyGridShouldNotProduceTable}：该场景行为改动即失败。
     */
    @Test
    void ruledEmptyGridShouldNotProduceTable() throws Exception {
        ParseSource source = parser.parse(pages.buildRuledTable(false));

        assertTrue(source.getElements().stream()
                .noneMatch(e -> ElementType.TABLE.name().equals(e.getType())));
    }

    /**
     * 守护 {@code sparseRuledGridShouldBeRejectedByFilledGate}：该场景行为改动即失败。
     */
    @Test
    void sparseRuledGridShouldBeRejectedByFilledGate() throws Exception {
        ParseSource source = parser.parse(pages.buildSparseRuledGrid());

        assertTrue(source.getElements().stream()
                .noneMatch(e -> ElementType.TABLE.name().equals(e.getType())));
        assertTrue(source.getFacts().stream()
                .anyMatch(f -> f.getEvidence() != null && f.getEvidence().contains("空白率")));
    }

    /**
     * 守护 {@code numericFirstRowShouldHaveNoHeader}：该场景行为改动即失败。
     */
    @Test
    void numericFirstRowShouldHaveNoHeader() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage pdfPage = new PDPage(new PDRectangle(595, 842));
            doc.addPage(pdfPage);
            try (PDPageContentStream cs = new PDPageContentStream(doc, pdfPage)) {
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                String[][] rows = {{"2024", "Q1", "100"}, {"2025", "Q2", "200"}, {"2026", "Q3", "300"}};
                float y = 700;
                for (String[] row : rows) {
                    cs.beginText();
                    cs.newLineAtOffset(60, y);
                    cs.showText(row[0]);
                    cs.newLineAtOffset(130, 0);
                    cs.showText(row[1]);
                    cs.newLineAtOffset(150, 0);
                    cs.showText(row[2]);
                    cs.endText();
                    y -= 24;
                }
            }
            ParseSource source = parser.parse(PdfParserTestFixture.save(doc));

            List<ParseElement> tables = source.getElements().stream()
                    .filter(e -> ElementType.TABLE.name().equals(e.getType()))
                    .toList();
            assertEquals(1, tables.size(), () -> "elements=" + source.getElements());
            assertEquals(null, tables.getFirst().getHeaderRow());
            assertTrue(tables.getFirst().getCells().stream()
                    .noneMatch(c -> Boolean.TRUE.equals(c.getIsHeader())));
        }
    }

    /**
     * 守护 {@code columnsShouldDropUnsupportedColumns}：该场景行为改动即失败。
     */
    @Test
    void columnsShouldDropUnsupportedColumns() {
        List<List<Token>> matrix = List.of(
                List.of(new Token("A", 10, 20), new Token("B", 100, 110)),
                List.of(new Token("C", 10, 20), new Token("D", 100, 110)),
                List.of(new Token("E", 10, 20), new Token("X", 200, 210)));

        List<TableCandidateDetector.Column> columns = TableCandidateDetector.columns(matrix, 2, 3.0);

        // 只有第三行出现的 200 段不构成列；保留的两列首尾相接覆盖整块宽度
        assertEquals(List.of(10.0, 100.0), lefts(columns));
        assertTrue(TableCandidateDetector.enoughLinesCoverColumns(matrix, columns));
    }

    /** 右对齐数字列：左起点逐行不同、右边界一致，两侧都作对齐线才认得出该列（{@code rightAlignedColumnShouldBeRecognized}）。 */
    @Test
    void rightAlignedColumnShouldBeRecognized() {
        List<List<Token>> matrix = List.of(
                List.of(new Token("Item", 10, 40), new Token("1,200.00", 80, 120)),
                List.of(new Token("Part", 10, 40), new Token("99.00", 101, 120)),
                List.of(new Token("Kit", 10, 40), new Token("4,800.00", 74, 120)));

        List<TableCandidateDetector.Column> columns = TableCandidateDetector.columns(matrix, 2, 3.0);

        assertEquals(2, columns.size());
        assertEquals(10.0, columns.getFirst().left(), 0.01);
        // 第二列覆盖三条记录的右边界，右对齐金额都落在列内
        assertTrue(matrix.stream().flatMap(List::stream)
                .filter(token -> token.text().contains("."))
                .allMatch(token -> TableCandidateDetector.contains(columns.getLast(), token)));
    }

    /**
     * 守护 {@code columnToleranceShouldFollowFontSize}：该场景行为改动即失败。
     */
    @Test
    void columnToleranceShouldFollowFontSize() {
        // 同一列起点在三行间抖动 10→14→18：24pt 字号下容差 6pt 能合成一列
        List<List<Token>> jittered = List.of(
                List.of(new Token("A", 10, 20), new Token("B", 100, 110)),
                List.of(new Token("C", 14, 24), new Token("D", 100, 110)),
                List.of(new Token("E", 18, 28), new Token("F", 100, 110)));

        assertEquals(2, TableCandidateDetector.columns(jittered, 2, 6.0).size());
        // 容差压到 1pt 时抖动列的支持度掉到下限以下，只剩稳定列
        assertEquals(1, TableCandidateDetector.columns(jittered, 2, 1.0).size());
    }

    /**
     * 守护 {@code smallFontToleranceShouldNotMergeNeighbourColumns}：该场景行为改动即失败。
     */
    @Test
    void smallFontToleranceShouldNotMergeNeighbourColumns() {
        List<List<Token>> matrix = List.of(
                List.of(new Token("A", 10, 14), new Token("B", 20, 24)),
                List.of(new Token("C", 10, 14), new Token("D", 20, 24)));

        assertEquals(2, TableCandidateDetector.columns(matrix, 2, 2.0).size());
        // 容差放大到 8pt 会把相邻两列并成一列（列间距小于容差时无法区分）
        assertTrue(TableCandidateDetector.columns(matrix, 2, 8.0).size() < 2);
    }

    private static List<Double> lefts(List<TableCandidateDetector.Column> columns) {
        return columns.stream().map(TableCandidateDetector.Column::left).toList();
    }

    /**
     * 守护 {@code detectHeaderRowShouldFollowNumericRule}：该场景行为改动即失败。
     */
    @Test
    void detectHeaderRowShouldFollowNumericRule() {
        assertEquals(0, TextTableBuilder.detectHeaderRow(
                List.of(List.of("项目", "分值"), List.of("A1", "30")), List.of(10.0, 10.0), 0.3));
        assertNull(TextTableBuilder.detectHeaderRow(
                List.of(List.of("2024", "100"), List.of("2025", "200")), List.of(10.0, 10.0), 0.3));
        assertEquals(0, TextTableBuilder.detectHeaderRow(
                List.of(List.of("项目", "分值"), List.of("A1", "B1")), List.of(14.0, 10.0), 0.3));
    }

    /**
     * 守护 {@code mergedCellsShouldMergeAcrossMissingDividers}：该场景行为改动即失败。
     */
    @Test
    void mergedCellsShouldMergeAcrossMissingDividers() {
        Grid grid = new Grid(
                List.of(0.0, 10.0, 20.0), List.of(0.0, 10.0, 20.0),
                List.of(List.of(true), List.of(false), List.of(false)),
                List.of(List.of(false, false), List.of(false, false)));

        List<RuledTableBuilder.CellRect> rects = RuledTableBuilder.mergedCells(grid);

        assertEquals(3, rects.size());
        assertEquals(0, rects.getFirst().row());
        assertEquals(2, rects.getFirst().colSpan());
        assertEquals(1, rects.getFirst().rowSpan());
    }

    private String cellText(ParseElement table, int row, int col) {
        return table.getCells().stream()
                .filter(c -> c.getRow() == row && c.getCol() == col)
                .map(ParseElement::getText).findFirst().orElse(null);
    }
}
