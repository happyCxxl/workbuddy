package com.workbuddy.worker.parser.pdf.assemble;

import com.workbuddy.common.domain.parse.BBox;
import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.enums.parse.ElementType;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.parser.ParseProperties;
import com.workbuddy.worker.parser.capability.CapabilityRegistry;
import com.workbuddy.worker.parser.pdf.detect.RuleLines;
import com.workbuddy.worker.parser.pdf.extract.LineTexts;
import com.workbuddy.worker.parser.pdf.extract.PdfGeometry;
import com.workbuddy.worker.parser.pdf.model.Grid;
import com.workbuddy.worker.parser.pdf.model.PageContent;
import com.workbuddy.worker.parser.pdf.model.PageLine;
import com.workbuddy.worker.parser.pdf.model.Region;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 有边框表（线网格路径）：线网格定行列与合并跨度，格内文本按格矩形归属
 * （行中心落带 + 字符落列区间）；网格拟合不出来时退回文本路径。
 */
public final class RuledTableBuilder {

    private RuledTableBuilder() {
    }

    /** 线框路径结算：线网格 → 合并格 → 格内文本 → 表元素（保底退回无边框路径） */
    static void flush(List<PageLine> run, Region region, PageContent page, List<ParseElement> elements,
                      ParseSource source, String fileId, ParseProperties properties,
                      CapabilityRegistry capabilityRegistry) {
        Grid grid = RuleLines.fit(page.segments(), region, properties.getTableLineCoverRatio());
        if (NullUtil.isNull(grid)) {
            PdfBodyAssembler.flushTextRun(run, page, elements, source, fileId, properties, capabilityRegistry);
            return;
        }
        List<CellRect> rects = mergedCells(grid);
        Map<CellRect, String> texts = new LinkedHashMap<>();
        List<List<String>> rowTexts = new ArrayList<>();
        for (int r = 0; r < grid.rowCount(); r++) {
            rowTexts.add(new ArrayList<>());
        }
        for (CellRect rect : rects) {
            String text = ruledCellText(run, grid, rect, LineTexts.Options.of(properties));
            texts.put(rect, text);
            rowTexts.get(rect.row()).add(text);
        }
        if (TableSupport.blankTable(rowTexts, properties)) {
            TableSupport.degrade(run, page, elements, source, fileId, "空白率过高（疑似框线/表单区域）");
            return;
        }
        List<Double> rowFontSizes = new ArrayList<>();
        for (int r = 0; r < grid.rowCount(); r++) {
            double top = grid.rows().get(r);
            double bottom = grid.rows().get(r + 1);
            rowFontSizes.add(run.stream()
                    .filter(line -> inBand(line, top, bottom))
                    .mapToDouble(PageLine::fontSize).average().orElse(0));
        }
        Integer headerRow = TextTableBuilder.detectHeaderRow(rowTexts, rowFontSizes,
                properties.getTableHeaderMaxNumericRatio());
        ParseElement table = ParseElement.of(ElementType.TABLE);
        table.setPage(page.pageNo());
        double left = grid.columns().getFirst();
        double right = grid.columns().getLast();
        double top = grid.rows().getFirst();
        double bottom = grid.rows().getLast();
        BBox bounds = new BBox(left, top, right - left, bottom - top);
        table.setBbox(page.geometry().toViewport(bounds));
        table.setRows(grid.rowCount());
        table.setCols(grid.colCount());
        table.setHeaderRow(headerRow);
        table.setCutAtPageBottom(PdfGeometry.cutAtPageBottom(bounds, page.pageHeight()));
        List<ParseElement> cells = new ArrayList<>();
        for (CellRect rect : rects) {
            ParseElement cell = ParseElement.of(ElementType.TABLE_CELL);
            cell.setText(texts.get(rect));
            cell.setRow(rect.row());
            cell.setCol(rect.col());
            if (rect.rowSpan() > 1) {
                cell.setRowSpan(rect.rowSpan());
            }
            if (rect.colSpan() > 1) {
                cell.setColSpan(rect.colSpan());
            }
            cell.setIsHeader(NullUtil.isNotNull(headerRow) && rect.row() == headerRow);
            double cellLeft = grid.columns().get(rect.col());
            double cellRight = grid.columns().get(rect.col() + rect.colSpan());
            double cellTop = grid.rows().get(rect.row());
            double cellBottom = grid.rows().get(rect.row() + rect.rowSpan());
            cell.setBbox(page.geometry().toViewport(
                    new BBox(cellLeft, cellTop, cellRight - cellLeft, cellBottom - cellTop)));
            cells.add(cell);
        }
        table.setCells(cells);
        elements.add(table);
    }

    /** 合并格划分：向右按列分隔线缺失扩张，再向下按行分隔线缺失扩张 */
    public static List<CellRect> mergedCells(Grid grid) {
        int rows = grid.rowCount();
        int cols = grid.colCount();
        boolean[][] used = new boolean[rows][cols];
        List<CellRect> rects = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (used[r][c]) {
                    continue;
                }
                int colSpan = 1;
                while (c + colSpan < cols
                        && Boolean.TRUE.equals(grid.columnGaps().get(r).get(c + colSpan - 1))) {
                    colSpan++;
                }
                int rowSpan = 1;
                while (r + rowSpan < rows && rowGapOpen(grid, r + rowSpan - 1, c, colSpan)) {
                    rowSpan++;
                }
                for (int rr = r; rr < r + rowSpan; rr++) {
                    for (int cc = c; cc < c + colSpan; cc++) {
                        used[rr][cc] = true;
                    }
                }
                rects.add(new CellRect(r, c, rowSpan, colSpan));
            }
        }
        return rects;
    }

    /** 行分隔线在给定列区间内是否全部缺失（缺失即纵向可合并） */
    private static boolean rowGapOpen(Grid grid, int gapIndex, int col, int colSpan) {
        for (int c = col; c < col + colSpan; c++) {
            if (!Boolean.TRUE.equals(grid.rowGaps().get(gapIndex).get(c))) {
                return false;
            }
        }
        return true;
    }

    /** 格内文本：行中心落在格行带的文本行，其落在格列区间的字符重建为文本，多行按行间规则拼接 */
    private static String ruledCellText(List<PageLine> run, Grid grid, CellRect rect, LineTexts.Options options) {
        double left = grid.columns().get(rect.col());
        double right = grid.columns().get(rect.col() + rect.colSpan());
        double top = grid.rows().get(rect.row());
        double bottom = grid.rows().get(rect.row() + rect.rowSpan());
        StringBuilder text = new StringBuilder();
        String previous = null;
        for (PageLine line : run) {
            if (!inBand(line, top, bottom)) {
                continue;
            }
            String piece = LineTexts.lineText(line.chars().stream()
                    .filter(ch -> ch.x() >= left && ch.x() < right)
                    .toList(), options);
            if (piece.isEmpty()) {
                continue;
            }
            if (NullUtil.isNotNull(previous)) {
                text.append(LineTexts.lineSeparator(previous, piece));
            }
            text.append(piece);
            previous = piece;
        }
        return text.toString();
    }

    /** 行中心是否落在带内 */
    private static boolean inBand(PageLine line, double top, double bottom) {
        double center = line.y() + line.height() / 2;
        return center >= top && center < bottom;
    }

    /** 合并格矩形：起始行列 + 跨度 */
    public record CellRect(int row, int col, int rowSpan, int colSpan) {
    }
}
