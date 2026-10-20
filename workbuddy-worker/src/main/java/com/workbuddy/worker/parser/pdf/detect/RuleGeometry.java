package com.workbuddy.worker.parser.pdf.detect;

import com.workbuddy.worker.parser.pdf.model.Segment;

/**
 * 线框几何判定：两条线段的相交/共线重叠与一维区间重叠，供连通分量与区域覆盖率共用。
 * 坐标统一为左上原点。
 */
final class RuleGeometry {

    /** 坐标与线位判定容差（pt） */
    private static final double TOLERANCE = 2.0;

    private RuleGeometry() {
    }

    /** 两线相交或共线重叠即视为同一线框组件 */
    static boolean intersects(Segment a, Segment b) {
        if (a.horizontal() == b.horizontal()) {
            if (a.horizontal()) {
                return Math.abs(a.y1() - b.y1()) <= TOLERANCE
                        && overlap(a.x1(), a.x2(), b.x1(), b.x2()) > TOLERANCE;
            }
            return Math.abs(a.x1() - b.x1()) <= TOLERANCE
                    && overlap(a.y1(), a.y2(), b.y1(), b.y2()) > TOLERANCE;
        }
        Segment horizontal = a.horizontal() ? a : b;
        Segment vertical = a.horizontal() ? b : a;
        double horizontalX1 = Math.min(horizontal.x1(), horizontal.x2());
        double horizontalX2 = Math.max(horizontal.x1(), horizontal.x2());
        double verticalY1 = Math.min(vertical.y1(), vertical.y2());
        double verticalY2 = Math.max(vertical.y1(), vertical.y2());
        return vertical.x1() >= horizontalX1 - TOLERANCE && vertical.x1() <= horizontalX2 + TOLERANCE
                && horizontal.y1() >= verticalY1 - TOLERANCE && horizontal.y1() <= verticalY2 + TOLERANCE;
    }

    /** 一维区间重叠长度（区间端点无序） */
    static double overlap(double a1, double a2, double b1, double b2) {
        return Math.min(Math.max(a1, a2), Math.max(b1, b2)) - Math.max(Math.min(a1, a2), Math.min(b1, b2));
    }
}
