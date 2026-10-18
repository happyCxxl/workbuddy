package com.workbuddy.worker.parser.pdf;
import com.workbuddy.common.domain.input.FileReference;
import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.enums.parse.ElementType;
import com.workbuddy.common.enums.parse.SignalType;
import com.workbuddy.common.domain.parse.signal.PageMetric;
import com.workbuddy.worker.parser.ParseContext;
import com.workbuddy.worker.parser.ParseProperties;
import com.workbuddy.worker.parser.pdf.assemble.RuledTableBuilder;
import com.workbuddy.worker.parser.pdf.assemble.TextTableBuilder;
import com.workbuddy.worker.parser.pdf.extract.LineTexts;
import com.workbuddy.worker.parser.pdf.model.CharInfo;
import com.workbuddy.worker.parser.pdf.detect.HeaderFooterDetector;
import com.workbuddy.worker.parser.pdf.model.Grid;
import com.workbuddy.worker.parser.pdf.model.PageLine;
import com.workbuddy.worker.parser.pdf.detect.RuleLines;
import com.workbuddy.worker.parser.pdf.detect.TableCandidateDetector;
import com.workbuddy.worker.parser.pdf.model.Token;
import com.workbuddy.worker.parser.pdf.layout.ProjectionPageLayoutAnalyzer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PDFBox 原生解析器单测：段落聚合、页级指标、HEADER 识别、表格列对齐启发式。
 */
class PdfBoxDocumentParserTest {

    private final PdfBoxDocumentParser parser =
            new PdfBoxDocumentParser(new ProjectionPageLayoutAnalyzer());

    private ParseContext context(byte[] data) {
        ParseContext context = new ParseContext();
        FileReference fileRef = new FileReference();
        fileRef.setFileId("F-1");
        fileRef.setFileName("t.pdf");
        fileRef.setMimeType("application/pdf");
        context.setFileRef(fileRef);
        context.setInputStream(new ByteArrayInputStream(data));
        context.setProperties(new ParseProperties());
        return context;
    }

    private byte[] buildPdf() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            for (int page = 0; page < 2; page++) {
                PDPage pdfPage = new PDPage(new PDRectangle(595, 842));
                doc.addPage(pdfPage);
                try (PDPageContentStream cs = new PDPageContentStream(doc, pdfPage)) {
                    // 页眉：两页同位置同文本（顶部）
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                    cs.newLineAtOffset(250, 800);
                    cs.showText("XX Project Header");
                    cs.endText();
                    // 正文两行
                    cs.beginText();
                    cs.newLineAtOffset(72, 700);
                    cs.showText("Chapter one body line " + page);
                    cs.endText();
                    cs.beginText();
                    cs.newLineAtOffset(72, 680);
                    cs.showText("Chapter one body continues " + page);
                    cs.endText();
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void pdfShouldParseParagraphsAndHeader() throws Exception {
        ParseContext context = context(buildPdf());
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

    @Test
    void scannedPdfShouldEmitZeroCharMetrics() throws Exception {
        // 空白页（无文本层）→ 页级字符数为 0（扫描判定交给 SignalDetector）
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(new PDRectangle(595, 842)));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            ParseSource source = parser.parse(context(out.toByteArray()));
            assertEquals(1, source.getUnitCount());
            assertEquals(0, source.getPageMetrics().getFirst().getCharCount());
        }
    }

    @Test
    void alignedColumnsShouldFormTable() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage pdfPage = new PDPage(new PDRectangle(595, 842));
            doc.addPage(pdfPage);
            try (PDPageContentStream cs = new PDPageContentStream(doc, pdfPage)) {
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                // 三行、三列，列距 > 20pt（宽列距信号）
                String[][] rows = {{"Score", "Standard", "Points"}, {"A1", "Bidding price", "30"}, {"A2", "Tech plan", "40"}};
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
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            ParseSource source = parser.parse(context(out.toByteArray()));

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

    @Test
    void repeatedFooterAndPageNumberShouldEmitFooterElements() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            for (int page = 0; page < 2; page++) {
                PDPage pdfPage = new PDPage(new PDRectangle(595, 842));
                doc.addPage(pdfPage);
                try (PDPageContentStream cs = new PDPageContentStream(doc, pdfPage)) {
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
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
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            ParseSource source = parser.parse(context(out.toByteArray()));

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

    @Test
    void footerWithTrailingPageNumberShouldStripSuffixAndDetect() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            for (int page = 0; page < 2; page++) {
                PDPage pdfPage = new PDPage(new PDRectangle(595, 842));
                doc.addPage(pdfPage);
                try (PDPageContentStream cs = new PDPageContentStream(doc, pdfPage)) {
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
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
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            ParseSource source = parser.parse(context(out.toByteArray()));

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

    @Test
    void tocLineShouldMarkTocCandidate() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage pdfPage = new PDPage(new PDRectangle(595, 842));
            doc.addPage(pdfPage);
            try (PDPageContentStream cs = new PDPageContentStream(doc, pdfPage)) {
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
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
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            ParseSource source = parser.parse(context(out.toByteArray()));

            assertTrue(source.getElements().stream()
                    .filter(e -> ElementType.PARAGRAPH.name().equals(e.getType()))
                    .anyMatch(e -> Boolean.TRUE.equals(e.getTocCandidate())
                            && e.getText().contains("Chapter One .... 1")));
        }
    }

    /** 行文本重建阈值：取解析阈值默认值 */
    private LineTexts.Options lineTextOptions() {
        return LineTexts.Options.of(new ParseProperties());
    }

    @Test
    void englishWordBoundariesShouldKeepSpaces() {
        List<CharInfo> chars = new ArrayList<>();
        chars.addAll(word("Chapter", 72));
        chars.addAll(word("one", 110));
        chars.addAll(word("body", 130));

        assertEquals("Chapter one body", LineTexts.lineText(chars, lineTextOptions()));
    }

    @Test
    void cjkWordBoundariesShouldNotInsertSpaces() {
        List<CharInfo> chars = new ArrayList<>();
        // 每个汉字各成一次回调（PDFBox 对 CJK 常逐字回调）
        chars.add(ch('知', 72, true, true));
        chars.add(ch('识', 82, true, true));
        chars.add(ch('库', 92, true, true));

        assertEquals("知识库", LineTexts.lineText(chars, lineTextOptions()));
    }

    @Test
    void mixedScriptBoundaryShouldKeepSpace() {
        List<CharInfo> chars = new ArrayList<>();
        chars.add(ch('知', 72, true, true));
        chars.add(ch('识', 82, true, true));
        chars.add(ch('库', 92, true, true));
        chars.addAll(word("knowledge", 110));

        assertEquals("知识库 knowledge", LineTexts.lineText(chars, lineTextOptions()));
    }

    @Test
    void lineSeparatorShouldFollowCjkRule() {
        assertTrue(LineTexts.lineSeparator(line("知识库"), line("下一句")).isEmpty());
        assertEquals(" ", LineTexts.lineSeparator(line("Chapter one"), line("body")));
    }

    @Test
    void cjkCodePointShouldCoverHanAndFullwidthPunctuation() {
        assertTrue(LineTexts.isCjk('中'));
        assertTrue(LineTexts.isCjk('。'));
        assertTrue(LineTexts.isCjk('，'));
        assertTrue(LineTexts.isCjk('ア'));
        assertFalse(LineTexts.isCjk('A'));
        assertFalse(LineTexts.isCjk(','));
    }

    @Test
    void headerFooterKeyShouldNormalizeWhitespace() {
        assertEquals("XX Project Header", HeaderFooterDetector.normalizeKey("  XX   Project \t Header "));
        assertEquals("知识库", HeaderFooterDetector.normalizeKey("\u200B知识库"));
        assertEquals("", HeaderFooterDetector.normalizeKey("   "));
    }

    @Test
    void borderedTableShouldUseRuleGridWithSpanAndSparseRow() throws Exception {
        ParseSource source = parser.parse(context(buildRuledTable()));

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

    @Test
    void ruledEmptyGridShouldNotProduceTable() throws Exception {
        ParseSource source = parser.parse(context(buildRuledTable(false)));

        assertTrue(source.getElements().stream()
                .noneMatch(e -> ElementType.TABLE.name().equals(e.getType())));
    }

    @Test
    void sparseRuledGridShouldBeRejectedByFilledGate() throws Exception {
        ParseSource source = parser.parse(context(buildSparseRuledGrid()));

        assertTrue(source.getElements().stream()
                .noneMatch(e -> ElementType.TABLE.name().equals(e.getType())));
        assertTrue(source.getFacts().stream()
                .anyMatch(f -> f.getEvidence() != null && f.getEvidence().contains("空白率")));
    }

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
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            ParseSource source = parser.parse(context(out.toByteArray()));

            List<ParseElement> tables = source.getElements().stream()
                    .filter(e -> ElementType.TABLE.name().equals(e.getType()))
                    .toList();
            assertEquals(1, tables.size(), () -> "elements=" + source.getElements());
            assertEquals(null, tables.getFirst().getHeaderRow());
            assertTrue(tables.getFirst().getCells().stream()
                    .noneMatch(c -> Boolean.TRUE.equals(c.getIsHeader())));
        }
    }

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

    /** 右对齐数字列：左起点逐行不同、右边界一致，两侧都作对齐线才认得出该列 */
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

    @Test
    void detectHeaderRowShouldFollowNumericRule() {
        assertEquals(0, TextTableBuilder.detectHeaderRow(
                List.of(List.of("项目", "分值"), List.of("A1", "30")), List.of(10.0, 10.0), 0.3));
        assertNull(TextTableBuilder.detectHeaderRow(
                List.of(List.of("2024", "100"), List.of("2025", "200")), List.of(10.0, 10.0), 0.3));
        assertEquals(0, TextTableBuilder.detectHeaderRow(
                List.of(List.of("项目", "分值"), List.of("A1", "B1")), List.of(14.0, 10.0), 0.3));
    }

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

    /** 造一张带框线的表：三行三列，首行横向合并、第二行只填一格 */
    private byte[] buildRuledTable() throws Exception {
        return buildRuledTable(true);
    }

    /** 造一张带框线的表；withText 为 false 时只画线不写文字（空白率门禁用例） */
    private byte[] buildRuledTable(boolean withText) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(595, 842));
            doc.addPage(page);
            float left = 72;
            float right = 372;
            float top = 842 - 200;
            float middle = 842 - 230;
            float lower = 842 - 260;
            float bottom = 842 - 290;
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setLineWidth(0.8f);
                drawLine(cs, left, top, right, top);
                drawLine(cs, left, middle, right, middle);
                drawLine(cs, left, lower, right, lower);
                drawLine(cs, left, bottom, right, bottom);
                drawLine(cs, left, bottom, left, top);
                drawLine(cs, right, bottom, right, top);
                drawLine(cs, 172, bottom, 172, middle);
                drawLine(cs, 272, bottom, 272, middle);
                if (withText) {
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                    cs.beginText();
                    cs.newLineAtOffset(left + 10, middle + 8);
                    cs.showText("Score sheet");
                    cs.endText();
                    cs.beginText();
                    cs.newLineAtOffset(left + 10, lower + 8);
                    cs.showText("A1");
                    cs.endText();
                    cs.beginText();
                    cs.newLineAtOffset(182, lower + 8);
                    cs.showText("Bidding price");
                    cs.endText();
                    cs.beginText();
                    cs.newLineAtOffset(282, bottom + 8);
                    cs.showText("30");
                    cs.endText();
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** 造一张 2 行 6 列的线框网格，只填一格（填充率低于假表门限） */
    private byte[] buildSparseRuledGrid() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(595, 842));
            doc.addPage(page);
            float left = 72;
            float right = 372;
            float top = 842 - 200;
            float middle = 842 - 245;
            float bottom = 842 - 290;
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setLineWidth(0.8f);
                drawLine(cs, left, top, right, top);
                drawLine(cs, left, middle, right, middle);
                drawLine(cs, left, bottom, right, bottom);
                for (int i = 0; i <= 6; i++) {
                    float x = left + i * 50;
                    drawLine(cs, x, bottom, x, top);
                }
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                cs.beginText();
                cs.newLineAtOffset(left + 10, top - 8);
                cs.showText("X");
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private void drawLine(PDPageContentStream cs, float x1, float y1, float x2, float y2) throws Exception {
        cs.moveTo(x1, y1);
        cs.lineTo(x2, y2);
        cs.stroke();
    }

    @Test
    void bodyLineEqualToHeaderTextShouldStayInBody() throws Exception {
        ParseSource source = parser.parse(context(
                buildPages(List.of(List.of(), List.of()), "XX Project Header", "XX Project Header")));

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

    @Test
    void bottomNumberOnSinglePageShouldStayInBody() throws Exception {
        ParseSource source = parser.parse(context(
                buildPages(List.of(List.of(new BottomNumber("2024", 100))), null, "Body text")));

        assertEquals(0, source.getElements().stream()
                .filter(e -> ElementType.FOOTER.name().equals(e.getType())).count());
        assertTrue(source.getElements().stream()
                        .filter(e -> ElementType.PARAGRAPH.name().equals(e.getType()))
                        .anyMatch(e -> e.getText().contains("2024")),
                () -> "elements=" + source.getElements());
    }

    @Test
    void pageNumberShouldRequireSamePositionAcrossPages() throws Exception {
        ParseSource source = parser.parse(context(buildPages(List.of(
                List.of(new BottomNumber("1", 290)),
                List.of(new BottomNumber("2", 290))), null, "Body text")));

        List<String> footers = source.getElements().stream()
                .filter(e -> ElementType.FOOTER.name().equals(e.getType()))
                .map(ParseElement::getText).toList();
        assertEquals(2, footers.size(), () -> "footers=" + footers);
        assertTrue(footers.contains("1"));
        assertTrue(footers.contains("2"));
    }

    @Test
    void pageNumberAtDifferentPositionsShouldStayInBody() throws Exception {
        ParseSource source = parser.parse(context(buildPages(List.of(
                List.of(new BottomNumber("1", 500)),
                List.of(new BottomNumber("2", 100))), null, "Body text")));

        assertEquals(0, source.getElements().stream()
                .filter(e -> ElementType.FOOTER.name().equals(e.getType())).count());
        List<String> paragraphs = source.getElements().stream()
                .filter(e -> ElementType.PARAGRAPH.name().equals(e.getType()))
                .map(ParseElement::getText).toList();
        assertTrue(paragraphs.contains("1"), () -> "paragraphs=" + paragraphs);
        assertTrue(paragraphs.contains("2"), () -> "paragraphs=" + paragraphs);
    }

    @Test
    void multipleBottomNumbersOnSamePageShouldNotBePageNumbers() throws Exception {
        ParseSource source = parser.parse(context(buildPages(List.of(
                List.of(new BottomNumber("10", 100), new BottomNumber("20", 400))), null, "Body text")));

        assertEquals(0, source.getElements().stream()
                .filter(e -> ElementType.FOOTER.name().equals(e.getType())).count());
        assertTrue(source.getElements().stream()
                        .filter(e -> ElementType.PARAGRAPH.name().equals(e.getType()))
                        .anyMatch(e -> e.getText().contains("10") && e.getText().contains("20")),
                () -> "elements=" + source.getElements());
    }

    @Test
    void twoColumnPageShouldReadByColumnAndNotBecomeTable() throws Exception {
        ParseSource source = parser.parse(context(buildTwoColumnPage()));

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

    @Test
    void spanningHeadingShouldSplitBandsInTwoColumnPage() throws Exception {
        ParseSource source = parser.parse(context(buildSpanningHeadingPage()));

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

    @Test
    void fullPageImageShouldBeMeasuredAsImageCoverage() throws Exception {
        ParseSource source = parser.parse(context(buildFullPageImagePdf()));

        PageMetric metric = source.getPageMetrics().getFirst();
        assertEquals(0, metric.getCharCount());
        assertTrue(metric.getImageAreaRatio() > 0.5, () -> "image=" + metric.getImageAreaRatio());
    }

    @Test
    void pageImageShouldEmitImageElementAndFact() throws Exception {
        ParseSource source = parser.parse(context(buildFullPageImagePdf()));

        ParseElement image = source.getElements().stream()
                .filter(e -> ElementType.IMAGE.name().equals(e.getType()))
                .findFirst().orElse(null);
        assertNotNull(image, () -> "elements=" + source.getElements());
        assertEquals(1, image.getPage());
        assertTrue(image.getNeedsOcr());
        assertNotNull(image.getBbox());
        assertTrue(image.getProvenance().getPath().contains("/image[0]"));
        assertTrue(source.getFacts().stream().anyMatch(f -> SignalType.OCR_IMAGE == f.getType()));
    }

    @Test
    void smallPageImageShouldNotEmitElement() throws Exception {
        ParseSource source = parser.parse(context(buildSmallImagePdf()));

        // 30×30pt 小图占页面积约 0.2%，低于图片元素门槛
        assertTrue(source.getElements().stream()
                .noneMatch(e -> ElementType.IMAGE.name().equals(e.getType())));
        assertTrue(source.getFacts().isEmpty(), () -> "facts=" + source.getFacts());
    }

    /** 多图页：图片元素逐张产出，OCR_IMAGE 事实按页聚合成一条（证据带张数） */
    @Test
    void multipleImagesOnPageShouldEmitSingleImageFact() throws Exception {
        ParseSource source = parser.parse(context(buildTwoImagePdf()));

        List<ParseElement> images = source.getElements().stream()
                .filter(e -> ElementType.IMAGE.name().equals(e.getType())).toList();
        assertEquals(2, images.size(), () -> "elements=" + source.getElements());
        var facts = source.getFacts().stream().filter(f -> SignalType.OCR_IMAGE == f.getType()).toList();
        assertEquals(1, facts.size(), () -> "facts=" + source.getFacts());
        assertEquals("page 1", facts.getFirst().getRegion());
        assertTrue(facts.getFirst().getEvidence().contains("2 张"),
                () -> "evidence=" + facts.getFirst().getEvidence());
    }

    /** 纯矢量页：无文本无图片但成框的矢量线撑出成片覆盖 → 页级指标给出矢量覆盖率 */
    @Test
    void vectorOnlyPageShouldReportVectorAreaRatio() throws Exception {
        ParseSource source = parser.parse(context(buildVectorChartPage()));

        PageMetric metric = source.getPageMetrics().getFirst();
        assertEquals(0, metric.getCharCount());
        assertEquals(0.0, metric.getImageAreaRatio(), 0.0001);
        assertTrue(metric.getVectorAreaRatio() >= 0.05, () -> "vector=" + metric.getVectorAreaRatio());
    }

    /** 造一页：无文本，只画一个 400×300pt 的矢量框（图表轮廓） */
    private byte[] buildVectorChartPage() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(595, 842));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setLineWidth(0.8f);
                float left = 100;
                float right = 500;
                float top = 700;
                float bottom = 400;
                drawLine(cs, left, top, right, top);
                drawLine(cs, left, bottom, right, bottom);
                drawLine(cs, left, bottom, left, top);
                drawLine(cs, right, bottom, right, top);
                drawLine(cs, left, 500, right, 500);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** 造一页：两张各占页面积约 18% 的图（都过图片元素门槛） */
    private byte[] buildTwoImagePdf() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(595, 842));
            doc.addPage(page);
            PDImageXObject xobject = LosslessFactory.createFromImage(doc, sampleImage());
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(xobject, 40, 500, 300, 300);
                cs.drawImage(xobject, 40, 150, 300, 300);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** 造一页：正文 + 小图（小图不产图片元素） */
    private byte[] buildSmallImagePdf() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(595, 842));
            doc.addPage(page);
            PDImageXObject xobject = LosslessFactory.createFromImage(doc, sampleImage());
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                cs.beginText();
                cs.newLineAtOffset(72, 700);
                cs.showText("body text for small image page");
                cs.endText();
                cs.drawImage(xobject, 72, 100, 30, 30);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** 8×8 图片（用例图片输入） */
    private BufferedImage sampleImage() {
        return new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
    }

    /** 造一页整页图片、无文本的 PDF */
    private byte[] buildFullPageImagePdf() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(595, 842));
            doc.addPage(page);
            BufferedImage image = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
            PDImageXObject xobject = LosslessFactory.createFromImage(doc, image);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(xobject, 0, 0, 595, 842);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** 造双栏 PDF：左右各三行正文（栏沟约 100pt） */
    private byte[] buildTwoColumnPage() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(595, 842));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                writeColumn(cs, 72, 700, leftColumn());
                writeColumn(cs, 330, 700, rightColumn());
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** 造双栏 PDF：页顶跨栏标题 + 左右各三行 + 中段跨栏标题 + 左右各两行 */
    private byte[] buildSpanningHeadingPage() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(595, 842));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                writeHeading(cs, 760, "Spanning Heading Number One Across Both Columns");
                writeColumn(cs, 72, 700, leftColumn());
                writeColumn(cs, 330, 700, rightColumn());
                writeHeading(cs, 640, "Spanning Heading Number Two Across Both Columns");
                writeColumn(cs, 72, 600, leftTail());
                writeColumn(cs, 330, 600, rightTail());
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private String[] leftColumn() {
        return new String[] {"Left column first line here", "Left column second line here",
                "Left column third line here"};
    }

    private String[] rightColumn() {
        return new String[] {"Right column first line here", "Right column second line here",
                "Right column third line here"};
    }

    private String[] leftTail() {
        return new String[] {"Left column fourth line here", "Left column fifth line here"};
    }

    private String[] rightTail() {
        return new String[] {"Right column fourth line here", "Right column fifth line here"};
    }

    /** 从 startY 起按 12pt 行距写一栏（10pt 字号） */
    private void writeColumn(PDPageContentStream cs, float x, float startY, String[] lines) throws Exception {
        cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
        float y = startY;
        for (String line : lines) {
            cs.beginText();
            cs.newLineAtOffset(x, y);
            cs.showText(line);
            cs.endText();
            y -= 12;
        }
    }

    /** 写一条横跨两栏的标题（14pt 字号，从左边距起足够长） */
    private void writeHeading(PDPageContentStream cs, float y, String text) throws Exception {
        cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
        cs.beginText();
        cs.newLineAtOffset(72, y);
        cs.showText(text);
        cs.endText();
    }

    /** 页底数字（文本 + x 位置） */
    private record BottomNumber(String text, float x) {
    }

    /**
     * 造 PDF：每页写入可选的页眉与正文，以及该页给定的页底纯数字行（同页多条按 y 错开成不同行）。
     *
     * @param bottomNumbers 每页的页底数字（空列表表示该页无页底数字）
     * @param headerText    页眉文本（null 表示不写）
     * @param bodyText      正文文本（null 表示不写）
     */
    private byte[] buildPages(List<List<BottomNumber>> bottomNumbers, String headerText,
                              String bodyText) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            for (int pageIndex = 0; pageIndex < bottomNumbers.size(); pageIndex++) {
                PDPage page = new PDPage(new PDRectangle(595, 842));
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                    if (headerText != null) {
                        cs.beginText();
                        cs.newLineAtOffset(72, 800);
                        cs.showText(headerText);
                        cs.endText();
                    }
                    if (bodyText != null) {
                        cs.beginText();
                        cs.newLineAtOffset(72, 400);
                        cs.showText(bodyText);
                        cs.endText();
                    }
                    List<BottomNumber> numbers = bottomNumbers.get(pageIndex);
                    for (int i = 0; i < numbers.size(); i++) {
                        cs.beginText();
                        cs.newLineAtOffset(numbers.get(i).x(), 30 + i * 12);
                        cs.showText(numbers.get(i).text());
                        cs.endText();
                    }
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** 造一个词的字符序列：词内 x 连续，首末码点带词边界标记 */
    private List<CharInfo> word(String text, double x) {
        List<CharInfo> chars = new ArrayList<>();
        for (int i = 0; i < text.length(); i++) {
            chars.add(ch(text.charAt(i), x + i * 5, i == 0, i == text.length() - 1));
        }
        return chars;
    }

    /** 造一个字符事实（固定行高与字号） */
    private CharInfo ch(int codePoint, double x, boolean wordStart, boolean wordEnd) {
        return new CharInfo(x, 700, 5, 10, 10, "Helvetica", false, codePoint);
    }

    /** 造一个行事实（只用到文本） */
    private PageLine line(String text) {
        return new PageLine(1, 72, 700, 100, 10, text, 10, "Helvetica", false,
                List.of(), List.of(), false);
    }
}
