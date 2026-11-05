package com.workbuddy.worker.parser.pdf.detect;

import com.workbuddy.worker.parser.pdf.model.Region;
import com.workbuddy.worker.parser.pdf.model.Segment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 线框区域页面边框判据：占满页面的框线是页面边框/背景，不是表格。
 */
class RuleRegionsPageFrameTest {

    private static final double PAGE_WIDTH = 595;

    private static final double PAGE_HEIGHT = 842;

    private static final double COVER_RATIO = 0.9;

    /**
     * 外框贴页面边缘、内框内缩 8.6pt 的整页双框：面积占满页面，不产出线框区域。
     *
     * <p>守护 {@code pageSizedFrameShouldNotBeRegion}。
     */
    @Test
    void pageSizedFrameShouldNotBeRegion() {
        List<Segment> frame = List.of(
                new Segment(0, 0, 594, 0), new Segment(0, 842, 594, 842),
                new Segment(0, 0, 0, 842), new Segment(594, 0, 594, 842),
                new Segment(8.6, 8.6, 584.7, 8.6), new Segment(8.6, 833, 584.7, 833),
                new Segment(8.6, 8.6, 8.6, 833), new Segment(584.7, 8.6, 584.7, 833));

        assertTrue(RuleRegions.regions(frame, COVER_RATIO, PAGE_WIDTH, PAGE_HEIGHT).isEmpty());
    }

    /**
     * 页面中部的 2×2 线框表仍算表格区域。
     *
     * <p>守护 {@code insetGridShouldStayRegion}。
     */
    @Test
    void insetGridShouldStayRegion() {
        List<Segment> grid = List.of(
                new Segment(72, 500, 372, 500), new Segment(72, 550, 372, 550),
                new Segment(72, 600, 372, 600),
                new Segment(72, 500, 72, 600), new Segment(222, 500, 222, 600),
                new Segment(372, 500, 372, 600));

        List<Region> regions = RuleRegions.regions(grid, COVER_RATIO, PAGE_WIDTH, PAGE_HEIGHT);

        assertEquals(1, regions.size());
        assertEquals(new Region(72, 500, 372, 600), regions.getFirst());
    }
}
