package com.workbuddy.worker.parser.pdf.detect;

import com.workbuddy.worker.parser.pdf.model.Segment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 共线短线段合并：把同一 y（横线）或同一 x（竖线）上相邻/重叠的单元格边线合并成整行/整列，
 * 供线框区域与线网格拟合使用。
 *
 * <p>PDF 中单元格常各自成矩形，边线是逐格短线段；而 {@link RuleGrid} 要求线段覆盖区域边长，
 * 不合并就会把规则表格判成“无区域”。
 */
public final class RuleSegmentMerger {

    /** 线位与首尾衔接容差（pt） */
    private static final double TOLERANCE = 2.0;

    private RuleSegmentMerger() {
    }

    /**
     * 合并共线短线段。
     *
     * @param segments 原始线段
     * @return 合并后的线段（横线按 y、竖线按 x 分组；首尾间隙超过容差不合并）
     */
    public static List<Segment> merge(List<Segment> segments) {
        List<Segment> horizontals = new ArrayList<>();
        List<Segment> verticals = new ArrayList<>();
        for (Segment segment : segments) {
            if (segment.horizontal()) {
                horizontals.add(segment);
            } else if (segment.vertical()) {
                verticals.add(segment);
            }
        }
        List<Segment> merged = new ArrayList<>();
        merged.addAll(mergeDirection(horizontals, true));
        merged.addAll(mergeDirection(verticals, false));
        return merged;
    }

    /**
     * 单方向合并。
     *
     * @param segments   同方向线段
     * @param horizontal 横线为 true
     * @return 合并后的线段
     */
    private static List<Segment> mergeDirection(List<Segment> segments, boolean horizontal) {
        List<Segment> ordered = new ArrayList<>(segments);
        ordered.sort(Comparator
                .comparingDouble((Segment s) -> coordinate(s, horizontal))
                .thenComparingDouble(s -> start(s, horizontal)));
        List<Segment> merged = new ArrayList<>();
        double currentCoordinate = Double.NaN;
        double currentStart = 0;
        double currentEnd = 0;
        for (Segment segment : ordered) {
            double coordinate = coordinate(segment, horizontal);
            double start = start(segment, horizontal);
            double end = end(segment, horizontal);
            if (!Double.isNaN(currentCoordinate)
                    && Math.abs(coordinate - currentCoordinate) <= TOLERANCE
                    && start <= currentEnd + TOLERANCE) {
                currentStart = Math.min(currentStart, start);
                currentEnd = Math.max(currentEnd, end);
                continue;
            }
            if (!Double.isNaN(currentCoordinate)) {
                merged.add(segment(currentCoordinate, currentStart, currentEnd, horizontal));
            }
            currentCoordinate = coordinate;
            currentStart = start;
            currentEnd = end;
        }
        if (!Double.isNaN(currentCoordinate)) {
            merged.add(segment(currentCoordinate, currentStart, currentEnd, horizontal));
        }
        return merged;
    }

    /** 线位：横线取 y 均值，竖线取 x 均值 */
    private static double coordinate(Segment segment, boolean horizontal) {
        return horizontal ? (segment.y1() + segment.y2()) / 2 : (segment.x1() + segment.x2()) / 2;
    }

    /** 线起点：横线取较小 x，竖线取较小 y */
    private static double start(Segment segment, boolean horizontal) {
        return horizontal ? Math.min(segment.x1(), segment.x2()) : Math.min(segment.y1(), segment.y2());
    }

    /** 线终点：横线取较大 x，竖线取较大 y */
    private static double end(Segment segment, boolean horizontal) {
        return horizontal ? Math.max(segment.x1(), segment.x2()) : Math.max(segment.y1(), segment.y2());
    }

    /** 由线位与起止重建线段 */
    private static Segment segment(double coordinate, double start, double end, boolean horizontal) {
        return horizontal
                ? new Segment(start, coordinate, end, coordinate)
                : new Segment(coordinate, start, coordinate, end);
    }
}
