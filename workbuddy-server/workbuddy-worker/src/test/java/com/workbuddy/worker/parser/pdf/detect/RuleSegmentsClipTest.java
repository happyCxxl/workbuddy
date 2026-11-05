package com.workbuddy.worker.parser.pdf.detect;

import com.workbuddy.worker.parser.pdf.model.Segment;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 线段可见性裁剪：页外（渲染时被裁掉）的线不参与线框检测，跨边界的截到页内。
 */
class RuleSegmentsClipTest {

    private static final double PAGE_WIDTH = 595;

    private static final double PAGE_HEIGHT = 842;

    /**
     * 页内线段原样保留。
     *
     * <p>守护 {@code visibleSegmentShouldStayUnchanged}。
     */
    @Test
    void visibleSegmentShouldStayUnchanged() {
        Segment clipped = RuleSegments.clip(new Segment(10, 100, 200, 100), PAGE_WIDTH, PAGE_HEIGHT);

        assertEquals(new Segment(10, 100, 200, 100), clipped);
    }

    /**
     * 整条落在页外的横线丢弃（页边框延展到下一页的形态）。
     *
     * <p>守护 {@code offPageSegmentShouldBeDropped}。
     */
    @Test
    void offPageSegmentShouldBeDropped() {
        assertNull(RuleSegments.clip(new Segment(10, 1666, 200, 1666), PAGE_WIDTH, PAGE_HEIGHT));
        assertNull(RuleSegments.clip(new Segment(700, 100, 700, 200), PAGE_WIDTH, PAGE_HEIGHT));
    }

    /**
     * 跨边界的线段截到页内。
     *
     * <p>守护 {@code crossingSegmentShouldBeTrimmedToPage}。
     */
    @Test
    void crossingSegmentShouldBeTrimmedToPage() {
        assertEquals(new Segment(0, 100, 595, 100),
                RuleSegments.clip(new Segment(-50, 100, 700, 100), PAGE_WIDTH, PAGE_HEIGHT));
        assertEquals(new Segment(100, 0, 100, 842),
                RuleSegments.clip(new Segment(100, -824, 100, 1666), PAGE_WIDTH, PAGE_HEIGHT));
    }
}
