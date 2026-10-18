package com.workbuddy.worker.parser.pdf.assemble;

import com.workbuddy.common.domain.parse.BBox;
import com.workbuddy.common.domain.parse.capability.LayoutRegion;
import com.workbuddy.worker.parser.pdf.model.PageLine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 模型阅读顺序落到页面行单测：行按中心命中区域取模型位次；区域外的行保持相对次序并排在末尾。
 */
class LayoutOrderSupportTest {

    /** 模型把第 1 区排在前：模型顺序与几何顺序相反时行序跟随模型 */
    @Test
    void linesShouldFollowModelOrder() {
        PageLine lower = line(100);
        PageLine upper = line(40);
        List<LayoutRegion> regions = List.of(box(30), box(95));

        List<PageLine> sorted = LayoutOrderSupport.reorder(List.of(upper, lower), regions, List.of(1, 0));

        assertEquals(List.of(lower, upper), sorted, "模型先第 1 区（下方）→ 下方的行排前");
    }

    /** 未命中任何区域的行排在命中的行之后，且彼此保持几何相对次序 */
    @Test
    void linesOutsideRegionsShouldKeepRelativeOrderAtEnd() {
        PageLine inside = line(100);
        PageLine outsideFirst = line(300);
        PageLine outsideSecond = line(200);

        List<PageLine> sorted = LayoutOrderSupport.reorder(List.of(outsideFirst, inside, outsideSecond),
                List.of(box(95)), List.of(0));

        assertEquals(List.of(inside, outsideFirst, outsideSecond), sorted);
    }

    /** 入参缺失：按原顺序返回（几何口径不动） */
    @Test
    void missingInputShouldKeepOriginalOrder() {
        List<PageLine> lines = List.of(line(100), line(40));

        assertEquals(lines, LayoutOrderSupport.reorder(lines, List.of(), List.of(0)));
        assertEquals(lines, LayoutOrderSupport.reorder(lines, List.of(box(95)), List.of()));
        assertEquals(List.of(), LayoutOrderSupport.reorder(List.of(), List.of(box(95)), List.of(0)));
    }

    /** 顺序里越界的区域序号被跳过，不抛异常 */
    @Test
    void outOfRangeRegionIndexShouldBeSkipped() {
        PageLine line = line(100);

        List<PageLine> sorted = LayoutOrderSupport.reorder(List.of(line), List.of(box(95)), List.of(7, 0));

        assertEquals(List.of(line), sorted);
    }

    private PageLine line(double y) {
        return new PageLine(1, 72, y, 200, 12, "text", 10, "Helvetica", false, List.of(), List.of(), false);
    }

    /** 造一个 y 区间为 [top, top+30)、x 区间为 [72, 272) 的区域（左上原点，y 越小越靠上） */
    private LayoutRegion box(double top) {
        LayoutRegion region = new LayoutRegion();
        region.setType("text");
        region.setBbox(new BBox(72, top, 200, 30));
        return region;
    }
}
