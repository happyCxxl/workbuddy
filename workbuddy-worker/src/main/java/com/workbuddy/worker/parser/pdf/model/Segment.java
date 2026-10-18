package com.workbuddy.worker.parser.pdf.model;

/**
 * 页面线段（左上原点坐标）。
 *
 * @param x1 起点 x
 * @param y1 起点 y
 * @param x2 终点 x
 * @param y2 终点 y
 */
public record Segment(double x1, double y1, double x2, double y2) {

    /** 线位判定容差（pt） */
    private static final double TOLERANCE = 2.0;

    /** 是否横线。 */
    public boolean horizontal() {
        return Math.abs(y1 - y2) <= TOLERANCE;
    }

    /** 是否竖线。 */
    public boolean vertical() {
        return Math.abs(x1 - x2) <= TOLERANCE;
    }

    /** 长度（取横竖较大跨度）。 */
    public double length() {
        return Math.max(Math.abs(x1 - x2), Math.abs(y1 - y2));
    }
}
