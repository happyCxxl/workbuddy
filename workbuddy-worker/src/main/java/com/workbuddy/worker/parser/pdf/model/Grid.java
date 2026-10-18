package com.workbuddy.worker.parser.pdf.model;

import java.util.List;

/**
 * 线网格：列边界与行边界（含外侧边界）+ 分隔线缺失矩阵。
 * columnGaps[r][c] 为 true 表示第 r 行里列 c 与 c+1 之间无竖线（两格横向合并）；
 * rowGaps[r][c] 为 true 表示第 c 列里行 r 与 r+1 之间无横线（两格纵向合并）。
 *
 * @param columns    列边界 x（升序，含左右外侧）
 * @param rows       行边界 y（升序，含上下外侧）
 * @param columnGaps 列分隔线缺失矩阵
 * @param rowGaps    行分隔线缺失矩阵
 */
public record Grid(List<Double> columns, List<Double> rows,
                   List<List<Boolean>> columnGaps, List<List<Boolean>> rowGaps) {

    /** 列数。 */
    public int colCount() {
        return columns.size() - 1;
    }

    /** 行数。 */
    public int rowCount() {
        return rows.size() - 1;
    }
}
