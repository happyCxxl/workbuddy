package com.workbuddy.worker.parser.pdf.detect;

import com.workbuddy.worker.parser.pdf.model.Segment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 共线短线段合并：相邻单元格边线合并成整行/整列，间隙超过容差保持独立。
 */
class RuleSegmentMergerTest {

    /**
     * 守护 {@code adjacentHorizontalSegmentsShouldMerge}：同一 y 上首尾相接的横线应合并成一条。
     */
    @Test
    void adjacentHorizontalSegmentsShouldMerge() {
        List<Segment> merged = RuleSegmentMerger.merge(List.of(
                new Segment(0, 10, 10, 10),
                new Segment(12, 10, 20, 10)));

        assertEquals(1, merged.size(), () -> "merged=" + merged);
        assertEquals(0, merged.getFirst().x1(), 0.01);
        assertEquals(20, merged.getFirst().x2(), 0.01);
    }

    /**
     * 守护 {@code separatedHorizontalSegmentsShouldStaySeparate}：间隙超过容差的横线不能合并。
     */
    @Test
    void separatedHorizontalSegmentsShouldStaySeparate() {
        List<Segment> merged = RuleSegmentMerger.merge(List.of(
                new Segment(0, 10, 10, 10),
                new Segment(13, 10, 20, 10)));

        assertEquals(2, merged.size(), () -> "merged=" + merged);
    }

    /**
     * 守护 {@code adjacentVerticalSegmentsShouldMerge}：同一 x 上首尾相接的竖线应合并成一条。
     */
    @Test
    void adjacentVerticalSegmentsShouldMerge() {
        List<Segment> merged = RuleSegmentMerger.merge(List.of(
                new Segment(10, 0, 10, 10),
                new Segment(10, 12, 10, 20)));

        assertEquals(1, merged.size(), () -> "merged=" + merged);
        assertEquals(0, merged.getFirst().y1(), 0.01);
        assertEquals(20, merged.getFirst().y2(), 0.01);
    }
}
