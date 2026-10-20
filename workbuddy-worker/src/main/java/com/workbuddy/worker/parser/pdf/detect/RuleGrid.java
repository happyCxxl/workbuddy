package com.workbuddy.worker.parser.pdf.detect;

import com.workbuddy.worker.parser.pdf.model.Grid;
import com.workbuddy.worker.parser.pdf.model.Region;
import com.workbuddy.worker.parser.pdf.model.Segment;

import java.util.ArrayList;
import java.util.List;

/**
 * 线网格拟合：区域内的纵横线按位置聚类成行列边界，逐格判定分隔线是否缺失（合并单元格）。
 */
public final class RuleGrid {

    /** 坐标与线位判定容差（pt） */
    private static final double TOLERANCE = 2.0;

    private RuleGrid() {
    }

    /**
     * 区域内求线网格。
     *
     * @param segments       区域内的线段
     * @param region         区域
     * @param lineCoverRatio 线段覆盖率门槛
     * @return 网格；列或行不足 2 段时为 null
     */
    public static Grid fit(List<Segment> segments, Region region, double lineCoverRatio) {
        List<Segment> verticals = RuleRegions.fullyInside(segments, region, true, lineCoverRatio);
        List<Segment> horizontals = RuleRegions.fullyInside(segments, region, false, lineCoverRatio);
        List<Double> columns = cluster(verticals.stream().map(Segment::x1).toList());
        List<Double> rows = cluster(horizontals.stream().map(Segment::y1).toList());
        if (columns.size() < 3 || rows.size() < 3) {
            return null;
        }
        List<List<Boolean>> columnGaps = new ArrayList<>();
        for (int r = 0; r + 1 < rows.size(); r++) {
            List<Boolean> rowGap = new ArrayList<>();
            for (int c = 1; c + 1 < columns.size(); c++) {
                rowGap.add(!RuleRegions.covered(verticals, true, columns.get(c),
                        rows.get(r), rows.get(r + 1), lineCoverRatio));
            }
            columnGaps.add(rowGap);
        }
        List<List<Boolean>> rowGaps = new ArrayList<>();
        for (int r = 1; r + 1 < rows.size(); r++) {
            List<Boolean> gaps = new ArrayList<>();
            for (int c = 0; c + 1 < columns.size(); c++) {
                gaps.add(!RuleRegions.covered(horizontals, false, rows.get(r),
                        columns.get(c), columns.get(c + 1), lineCoverRatio));
            }
            rowGaps.add(gaps);
        }
        return new Grid(columns, rows, columnGaps, rowGaps);
    }

    /** 一维聚类：容差内合并，取均值作代表位（行列边界对齐位置） */
    private static List<Double> cluster(List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        List<Double> centers = new ArrayList<>();
        double sum = 0;
        int count = 0;
        for (double value : sorted) {
            if (count > 0 && value - sum / count > TOLERANCE) {
                centers.add(sum / count);
                sum = 0;
                count = 0;
            }
            sum += value;
            count++;
        }
        if (count > 0) {
            centers.add(sum / count);
        }
        return centers;
    }
}
