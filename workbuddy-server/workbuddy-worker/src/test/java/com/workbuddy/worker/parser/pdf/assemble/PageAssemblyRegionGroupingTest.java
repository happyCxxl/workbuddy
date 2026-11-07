package com.workbuddy.worker.parser.pdf.assemble;

import com.workbuddy.common.domain.input.FileReference;
import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.domain.parse.signal.PageMetric;
import com.workbuddy.common.enums.parse.ElementType;
import com.workbuddy.worker.parser.ParseContext;
import com.workbuddy.worker.parser.ParseProperties;
import com.workbuddy.worker.parser.pdf.detect.HeaderFooterDetector;
import com.workbuddy.worker.parser.pdf.detect.HeaderFooterKeys;
import com.workbuddy.worker.parser.pdf.model.CharInfo;
import com.workbuddy.worker.parser.pdf.model.PageContent;
import com.workbuddy.worker.parser.pdf.model.PageLine;
import com.workbuddy.worker.parser.pdf.model.PdfPageGeometry;
import com.workbuddy.worker.parser.pdf.model.Segment;
import com.workbuddy.worker.parser.pdf.page.BlockClassifyStep;
import com.workbuddy.worker.parser.pdf.page.BlockRenderStep;
import com.workbuddy.worker.parser.pdf.page.BodyLineStep;
import com.workbuddy.worker.parser.pdf.page.DuplicateTableStep;
import com.workbuddy.worker.parser.pdf.page.ElementIdStep;
import com.workbuddy.worker.parser.pdf.page.FooterAppendStep;
import com.workbuddy.worker.parser.pdf.page.FooterCollectStep;
import com.workbuddy.worker.parser.pdf.page.HeaderBandStep;
import com.workbuddy.worker.parser.pdf.page.ImageStep;
import com.workbuddy.worker.parser.pdf.page.PageContext;
import com.workbuddy.worker.parser.pdf.page.PagePipeline;
import com.workbuddy.worker.parser.pdf.page.ReadingOrderStep;
import com.workbuddy.worker.parser.pdf.page.RegionStep;
import com.workbuddy.worker.parser.pdf.page.TableProvenanceStep;
import com.workbuddy.worker.testkit.PageUnitFixtures;
import com.workbuddy.worker.testkit.PropertiesFixtures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 线框区域结算不看行序：区域内行被阅读顺序打散（分栏重排把单元格续行挪到页尾）时，
 * 表格仍须一次性结算全部行，不得产出缺格表格 + 残段降级段落。
 */
class PageAssemblyRegionGroupingTest {

    /** 网格列线 x（4 列） */
    private static final double[] COLUMNS = {74, 110, 190, 250, 520};

    /** 网格行线 y（3 行） */
    private static final double[] ROWS = {260, 300, 340, 380};

    /**
     * 守护「同一线框区域只结算一次」：被挪到页尾的单元格续行仍归入表格，单元格文本不缺，
     * 且不产出降级段落（{@code displacedRegionLineShouldStillLandInTable}）。
     */
    @Test
    void displacedRegionLineShouldStillLandInTable() {
        List<PageLine> lines = new ArrayList<>();
        lines.add(line("H0", 80, 265));
        lines.add(line("H1", 120, 265));
        lines.add(line("H2", 200, 265));
        lines.add(line("H3", 260, 265));
        lines.add(line("A", 80, 305));
        lines.add(line("B", 120, 305));
        lines.add(line("C", 200, 305));
        lines.add(line("D", 260, 305));
        lines.add(line("E", 80, 345));
        lines.add(line("F", 120, 345));
        lines.add(line("G", 200, 345));
        lines.add(line("T1", 260, 345));
        lines.add(line("Body one", 80, 420));
        lines.add(line("Body two", 80, 435));
        // 阅读顺序把同一单元格的续行挪到了页尾（分栏重排后的位置），它仍属于表格区域
        lines.add(line("T2", 260, 355));

        PageContent page = page(lines);
        PageContext context = new PageContext(page, PageUnitFixtures.of(page),
                new HeaderFooterKeys(Set.of(), new HeaderFooterDetector.FooterKeys(Set.of(), List.of())),
                properties(), null, parseContext(properties()), null, "F-1",
                ParseSource.nativeSource("test"));
        new PagePipeline(List.of(
                new HeaderBandStep(),
                new FooterCollectStep(),
                new BodyLineStep(),
                new RegionStep(),
                new ReadingOrderStep(),
                new BlockClassifyStep(),
                new BlockRenderStep(),
                new FooterAppendStep(),
                new ImageStep(),
                new DuplicateTableStep(),
                new ElementIdStep(),
                new TableProvenanceStep())).run(context);
        List<ParseElement> elements = context.getElements();

        List<ParseElement> tables = elements.stream()
                .filter(element -> ElementType.TABLE.name().equals(element.getType()))
                .toList();
        assertEquals(1, tables.size(), () -> "tables=" + tables.size() + " elements=" + elements);
        assertEquals("T1 T2", cellText(tables.getFirst(), 2, 3).replace("\n", " "),
                () -> "cells=" + tables.getFirst().getCells());
        assertEquals(0, elements.stream().filter(element -> Boolean.TRUE.equals(element.getDegraded())).count(),
                () -> "elements=" + elements);
        assertTrue(elements.stream().anyMatch(element -> element.getText() != null
                && element.getText().contains("Body one")), () -> "elements=" + elements);
    }

    /** 取单元格文本 */
    private static String cellText(ParseElement table, int row, int col) {
        return table.getCells().stream()
                .filter(cell -> cell.getRow() == row && cell.getCol() == col)
                .map(ParseElement::getText)
                .findFirst()
                .orElse("");
    }

    /** 造一行：字符按 5pt 字宽从给定左边界连续排布 */
    private static PageLine line(String text, double x, double y) {
        List<CharInfo> chars = new ArrayList<>();
        double cursor = x;
        for (int i = 0; i < text.length(); i++) {
            chars.add(new CharInfo(cursor, y, 5, 10, 10, "Helvetica", false, text.charAt(i)));
            cursor += 5;
        }
        return new PageLine(1, x, y, cursor - x, 10, text, 10, "Helvetica", false,
                List.of(), chars, false);
    }

    /** 造单页内容：线框网格（3 行 4 列）由线段给出 */
    private static PageContent page(List<PageLine> lines) {
        List<Segment> segments = new ArrayList<>();
        for (double row : ROWS) {
            segments.add(new Segment(COLUMNS[0], row, COLUMNS[COLUMNS.length - 1], row));
        }
        for (double column : COLUMNS) {
            segments.add(new Segment(column, ROWS[0], column, ROWS[ROWS.length - 1]));
        }
        return new PageContent(1, 595, 842, new PdfPageGeometry(595, 842, 0), new PageMetric(),
                lines, segments, List.of());
    }

    /** 解析阈值（真实配置面） */
    private static ParseProperties properties() {
        return PropertiesFixtures.parse();
    }

    /** 解析上下文：只需文件 ID 与阈值配置。 */
    private static ParseContext parseContext(ParseProperties properties) {
        ParseContext context = new ParseContext();
        FileReference fileRef = new FileReference();
        fileRef.setFileId("F-1");
        context.setFileRef(fileRef);
        context.setProperties(properties);
        return context;
    }
}
