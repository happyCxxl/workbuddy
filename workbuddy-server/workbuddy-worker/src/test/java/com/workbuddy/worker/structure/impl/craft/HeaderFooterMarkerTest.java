package com.workbuddy.worker.structure.impl.craft;

import com.workbuddy.common.domain.parse.BBox;
import com.workbuddy.common.domain.parse.PageDimension;
import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.domain.parse.ParseUnitResult;
import com.workbuddy.common.domain.parse.UnitLine;
import com.workbuddy.common.enums.parse.ElementType;
import com.workbuddy.common.enums.parse.UnitType;
import com.workbuddy.worker.structure.craft.HeaderFooterDetector.BandRules;
import com.workbuddy.worker.structure.craft.HeaderFooterMarker;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 页眉页脚标注：整体落在带内且文本命中已确认键的元素改类型；跨带元素保持正文。
 */
class HeaderFooterMarkerTest {

    private static final double PAGE_HEIGHT = 842;
    private static final BandRules RULES = new BandRules(0.08, 0.08, 2, 2, 5, 1.5);

    /** 重复页眉/页脚与孤立页码：整行落在带内的元素改类型（{@code markShouldTypeBandElements}）。 */
    @Test
    void markShouldTypeBandElements() {
        ParseSource source = source(
                element("h1", "报告", 72, 40, 100, 12, 1),
                element("p1", "正文内容", 72, 200, 400, 12, 1),
                element("f1", "某某公司 1", 72, 800, 120, 12, 1),
                element("h2", "报告", 72, 40, 100, 12, 2),
                element("p2", "正文内容", 72, 200, 400, 12, 2),
                element("f2", "某某公司 2", 72, 800, 120, 12, 2));

        int marked = HeaderFooterMarker.mark(List.of(source), RULES);

        assertEquals(4, marked);
        assertEquals(ElementType.HEADER.name(), type(source, "h1"));
        assertEquals(ElementType.PARAGRAPH.name(), type(source, "p1"));
        assertEquals(ElementType.FOOTER.name(), type(source, "f1"));
        assertEquals(ElementType.HEADER.name(), type(source, "h2"));
        assertEquals(ElementType.FOOTER.name(), type(source, "f2"));
    }

    /** 跨带元素（页眉带里开始、正文继续）保持正文，不因起点在带内被标页眉（{@code spanningElementShouldStayBody}）。 */
    @Test
    void spanningElementShouldStayBody() {
        ParseSource source = source(
                element("sp1", "报告 正文内容", 72, 40, 300, 120, 1),
                element("p1", "正文内容", 72, 300, 400, 12, 1),
                element("sp2", "报告 正文内容", 72, 40, 300, 120, 2),
                element("p2", "正文内容", 72, 300, 400, 12, 2));

        HeaderFooterMarker.mark(List.of(source), RULES);

        assertEquals(ElementType.PARAGRAPH.name(), type(source, "sp1"));
        assertEquals(ElementType.PARAGRAPH.name(), type(source, "sp2"));
    }

    /** 只出现一页的页带文本不标页眉页脚（{@code oneOffBandTextShouldStayBody}）。 */
    @Test
    void oneOffBandTextShouldStayBody() {
        ParseSource source = source(
                element("h1", "第一页附注", 72, 40, 100, 12, 1),
                element("h2", "第二页附注", 72, 40, 100, 12, 2));

        int marked = HeaderFooterMarker.mark(List.of(source), RULES);

        assertEquals(0, marked);
        assertEquals(ElementType.PARAGRAPH.name(), type(source, "h1"));
    }

    private ParseSource source(ParseElement... elements) {
        ParseSource source = ParseSource.nativeSource("test");
        source.setUnitType(UnitType.PAGE);
        source.setUnits(List.of(unit(1), unit(2)));
        source.setElements(new ArrayList<>(List.of(elements)));
        source.summarizeUnits();
        return source;
    }

    private ParseUnitResult unit(int index) {
        ParseUnitResult unit = new ParseUnitResult();
        unit.setUnitIndex(index);
        unit.setType(UnitType.PAGE);
        unit.setLayoutHeight(PAGE_HEIGHT);
        unit.setDimension(new PageDimension(index, 595.0, PAGE_HEIGHT));
        unit.setLines(List.of(
                new UnitLine("报告", 72, 40, 100, 12),
                new UnitLine("正文内容", 72, 200, 400, 12),
                new UnitLine("某某公司 " + index, 72, 800, 120, 12)));
        return unit;
    }

    private ParseElement element(String id, String text, double x, double y, double width, double height, int page) {
        ParseElement element = ParseElement.of(id, ElementType.PARAGRAPH);
        element.setText(text);
        element.setPage(page);
        element.setBbox(new BBox(x, y, width, height));
        return element;
    }

    private String type(ParseSource source, String id) {
        return source.getElements().stream()
                .filter(element -> id.equals(element.getId()))
                .findFirst()
                .orElseThrow()
                .getType();
    }
}
