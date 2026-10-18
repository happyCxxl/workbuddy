package com.workbuddy.worker.parser.pdf.model;

/**
 * 页面矩形区域（左上原点坐标）：线框区域或图片占位矩形。
 *
 * @param left   左边界
 * @param top    上边界
 * @param right  右边界
 * @param bottom 下边界
 */
public record Region(double left, double top, double right, double bottom) {

    /** 区域判定容差（pt） */
    private static final double TOLERANCE = 2.0;

    /** 点是否落在区域内（含容差） */
    public boolean contains(double x, double y) {
        return x >= left - TOLERANCE && x <= right + TOLERANCE
                && y >= top - TOLERANCE && y <= bottom + TOLERANCE;
    }
}
