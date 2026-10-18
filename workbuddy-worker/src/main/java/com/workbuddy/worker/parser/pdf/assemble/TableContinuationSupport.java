package com.workbuddy.worker.parser.pdf.assemble;

import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.utils.NullUtil;

import java.util.List;

/**
 * 跨页续表判定（PDF 产物后处理）：相邻页同列表头重复、或上一页表格贴页底且列区间重合，
 * 即把本表标为续表（{@code headerRepeated=true}），供组装环节接续。
 *
 * @author cxxl
 */
public final class TableContinuationSupport {

    private TableContinuationSupport() {
    }

    /**
     * 标记续表：按页序扫描顶层 {@code TABLE} 元素，与上一页最近一张同列表比较。
     *
     * @param elements       解析产物元素（原地写 {@code headerRepeated}）
     * @param overlapRatio   列 x 区间重合比例下限（贴页底续表的判据之一）
     */
    public static void markContinuedTables(List<ParseElement> elements, double overlapRatio) {
        ParseElement previous = null;
        for (ParseElement element : elements) {
            if (!"TABLE".equals(element.getType())) {
                continue;
            }
            if (isContinuation(previous, element, overlapRatio)) {
                element.setHeaderRepeated(true);
            }
            previous = element;
        }
    }

    /** 续表判据：列数一致，且表头行文本重复、或上一页表格贴页底且列区间重合 */
    private static boolean isContinuation(ParseElement previous, ParseElement current, double overlapRatio) {
        if (NullUtil.isNull(previous) || NullUtil.isNull(current.getCols())
                || !current.getCols().equals(previous.getCols())) {
            return false;
        }
        if (!firstRow(current).isEmpty() && firstRow(current).equals(firstRow(previous))) {
            return true;
        }
        return Boolean.TRUE.equals(previous.getCutAtPageBottom()) && overlaps(previous, current, overlapRatio);
    }

    /** 表头行文本（第 0 行单元格） */
    private static List<String> firstRow(ParseElement table) {
        if (NullUtil.isNull(table.getCells())) {
            return List.of();
        }
        return table.getCells().stream()
                .filter(cell -> Integer.valueOf(0).equals(cell.getRow()))
                .map(cell -> NullUtil.isNull(cell.getText()) ? "" : cell.getText())
                .toList();
    }

    /** 列区间重合：两表 bbox 的 x 投影交集占较窄一侧的比例 */
    private static boolean overlaps(ParseElement previous, ParseElement current, double overlapRatio) {
        if (NullUtil.isNull(previous.getBbox()) || NullUtil.isNull(current.getBbox())) {
            return false;
        }
        double left = Math.max(previous.getBbox().getX(), current.getBbox().getX());
        double right = Math.min(previous.getBbox().getX() + previous.getBbox().getWidth(),
                current.getBbox().getX() + current.getBbox().getWidth());
        double narrower = Math.min(previous.getBbox().getWidth(), current.getBbox().getWidth());
        return narrower > 0 && (right - left) / narrower >= overlapRatio;
    }
}
