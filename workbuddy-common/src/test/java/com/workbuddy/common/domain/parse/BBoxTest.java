package com.workbuddy.common.domain.parse;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 边界框重叠判定单测。
 *
 * <p>守护 {@code overlaps} 的口径：投影区间双向相交才算重叠（含容差）、
 * 空框不与任何框重叠——页眉页脚带判定与图片占位去重共用此口径。
 */
class BBoxTest {

    /**
     * 相交的两框重叠。
     *
     * <p>守护 {@code overlaps}：把相交判成不相交即失败（带内元素漏判）。
     */
    @Test
    void overlappingBoxesShouldOverlap() {
        BBox first = new BBox(0, 0, 10, 10);
        BBox second = new BBox(5, 5, 10, 10);

        assertTrue(first.overlaps(second, 0));
        assertTrue(second.overlaps(first, 0));
    }

    /**
     * 完全分离的两框不重叠。
     *
     * <p>守护 {@code overlaps}：把分离判成重叠即失败（带判定把正文误收）。
     */
    @Test
    void disjointBoxesShouldNotOverlap() {
        BBox first = new BBox(0, 0, 10, 10);
        BBox second = new BBox(100, 100, 10, 10);

        assertFalse(first.overlaps(second, 0));
    }

    /**
     * 单轴分离（另一轴相交）不算重叠。
     *
     * <p>守护 {@code overlaps} 的双向投影：丢掉任一轴判断即失败（左右相邻的行误判重叠）。
     */
    @Test
    void singleAxisSeparationShouldNotOverlap() {
        BBox left = new BBox(0, 0, 10, 10);
        BBox right = new BBox(20, 0, 10, 10);
        assertFalse(left.overlaps(right, 0));

        BBox top = new BBox(0, 0, 10, 10);
        BBox bottom = new BBox(0, 20, 10, 10);
        assertFalse(top.overlaps(bottom, 0));
    }

    /**
     * 容差把贴近的框拉成重叠。
     *
     * <p>守护 {@code overlaps} 的容差语义：容差只扩进不扩出，改方向即失败。
     */
    @Test
    void toleranceShouldBridgeSmallGaps() {
        BBox first = new BBox(0, 0, 10, 10);
        BBox nearby = new BBox(11, 0, 10, 10);

        assertFalse(first.overlaps(nearby, 0));
        assertTrue(first.overlaps(nearby, 2));
    }

    /**
     * 空框不与任何框重叠。
     *
     * <p>守护 {@code overlaps} 的空值口径：把空框当重叠即失败（Office 无坐标元素误伤判定）。
     */
    @Test
    void nullBoxShouldNeverOverlap() {
        assertFalse(new BBox(0, 0, 10, 10).overlaps(null, 0));
    }
}
