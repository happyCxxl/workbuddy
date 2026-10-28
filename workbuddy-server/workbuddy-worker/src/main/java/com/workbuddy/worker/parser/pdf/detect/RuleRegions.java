package com.workbuddy.worker.parser.pdf.detect;

import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.parser.pdf.model.Grid;
import com.workbuddy.worker.parser.pdf.model.Region;
import com.workbuddy.worker.parser.pdf.model.Segment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 线框区域：过滤装饰性短线与矢量密集页，把相交线段聚成组件、取外接矩形，
 * 并判定区域内线段的边长覆盖率与包含关系。
 */
public final class RuleRegions {

    /** 坐标与线位判定容差（pt） */
    private static final double TOLERANCE = 2.0;

    /** 参与网格的最短线段（pt）：滤掉装饰性短线 */
    private static final double MIN_SEGMENT_LENGTH = 3.0;

    /** 单页参与线网格的线段上限：超出即跳过线检测（矢量密集页退回文本表格路径） */
    private static final int MAX_RULE_SEGMENTS = 2000;

    private RuleRegions() {
    }

    /**
     * 线框区域：相交线条聚成组件，取外接矩形并保留能形成至少 2 列 2 行网格的组件。
     *
     * @param segments       线段
     * @param lineCoverRatio 线段覆盖率门槛
     * @return 线框区域，按上边界升序
     */
    public static List<Region> regions(List<Segment> segments, double lineCoverRatio) {
        List<Segment> usable = segments.stream()
                .filter(s -> s.length() >= MIN_SEGMENT_LENGTH)
                .toList();
        if (usable.size() > MAX_RULE_SEGMENTS) {
            return List.of();
        }
        List<Region> regions = new ArrayList<>();
        for (List<Segment> component : RuleSegments.components(usable)) {
            Region region = bounds(component);
            Grid grid = RuleGrid.fit(component, region, lineCoverRatio);
            if (NullUtil.isNotNull(grid)) {
                regions.add(region);
            }
        }
        regions.sort(Comparator.comparingDouble(Region::top));
        return regions;
    }

    /** 区域内的线：按方向筛出，并要求其长度覆盖区域对应边长的比例 */
    static List<Segment> fullyInside(List<Segment> segments, Region region, boolean vertical,
                                     double lineCoverRatio) {
        List<Segment> result = new ArrayList<>();
        for (Segment segment : segments) {
            if (vertical != segment.vertical()) {
                continue;
            }
            if (vertical) {
                if (region.left() - TOLERANCE <= segment.x1() && segment.x1() <= region.right() + TOLERANCE
                        && segment.length() >= (region.bottom() - region.top()) * lineCoverRatio) {
                    result.add(segment);
                }
            } else if (region.top() - TOLERANCE <= segment.y1() && segment.y1() <= region.bottom() + TOLERANCE
                    && segment.length() >= (region.right() - region.left()) * lineCoverRatio) {
                result.add(segment);
            }
        }
        return result;
    }

    /** 在给定带内是否存在覆盖比例达标的线 */
    static boolean covered(List<Segment> segments, boolean vertical, double at,
                           double bandFrom, double bandTo, double lineCoverRatio) {
        double band = bandTo - bandFrom;
        for (Segment segment : segments) {
            if (vertical != segment.vertical()) {
                continue;
            }
            double position = vertical ? segment.x1() : segment.y1();
            if (Math.abs(position - at) > TOLERANCE) {
                continue;
            }
            double low = vertical ? Math.min(segment.y1(), segment.y2()) : Math.min(segment.x1(), segment.x2());
            double high = vertical ? Math.max(segment.y1(), segment.y2()) : Math.max(segment.x1(), segment.x2());
            double overlap = Math.min(high, bandTo) - Math.max(low, bandFrom);
            if (band <= 0 || overlap >= band * lineCoverRatio) {
                return true;
            }
        }
        return false;
    }

    /** 组件的外接矩形（左上原点） */
    private static Region bounds(List<Segment> segments) {
        double left = segments.stream().mapToDouble(s -> Math.min(s.x1(), s.x2())).min().orElse(0);
        double right = segments.stream().mapToDouble(s -> Math.max(s.x1(), s.x2())).max().orElse(0);
        double top = segments.stream().mapToDouble(s -> Math.min(s.y1(), s.y2())).min().orElse(0);
        double bottom = segments.stream().mapToDouble(s -> Math.max(s.y1(), s.y2())).max().orElse(0);
        return new Region(left, top, right, bottom);
    }
}
