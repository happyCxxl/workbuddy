package com.workbuddy.worker.structure.impl.craft;
import com.workbuddy.common.enums.structure.UnifiedElementType;

import com.workbuddy.common.domain.parse.BBox;
import com.workbuddy.common.domain.structure.UnifiedElement;
import com.workbuddy.worker.structure.AssembleContext;
import com.workbuddy.worker.structure.StructureProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 阅读顺序单测：XY-cut 双栏页面（左栏上→下、右栏上→下）+ 无坐标元素直通。
 */
class ReadingOrderResolverImplTest {

    private ReadingOrderResolverImpl resolver;
    private StructureProperties properties;

    @BeforeEach
    void setUp() {
        properties = new StructureProperties();
        resolver = new ReadingOrderResolverImpl();
    }

    private AssembleContext context() {
        AssembleContext context = new AssembleContext();
        context.setProperties(properties);
        return context;
    }

    private UnifiedElement element(String id, int page, double x, double y, double width, double height) {
        UnifiedElement element = new UnifiedElement();
        element.setId(id);
        element.setType(UnifiedElementType.PARAGRAPH.name());
        element.setPage(page);
        element.setBbox(new BBox(x, y, width, height));
        return element;
    }

    @Test
    void twoColumnPageShouldReadColumnByColumn() {
        // 双栏：左栏两段（x=72）、右栏两段（x=320），y 交错
        UnifiedElement left1 = element("l1", 1, 72, 100, 200, 20);
        UnifiedElement right1 = element("r1", 1, 320, 90, 200, 20);
        UnifiedElement left2 = element("l2", 1, 72, 140, 200, 20);
        UnifiedElement right2 = element("r2", 1, 320, 130, 200, 20);

        List<UnifiedElement> ordered = resolver.resolve(List.of(left1, right1, left2, right2), context());

        // 期望：左栏上→下、右栏上→下（列块宽 200、块间距 > 12pt）
        assertEquals(List.of("l1", "l2", "r1", "r2"),
                ordered.stream().map(UnifiedElement::getId).toList());
    }

    @Test
    void noBBoxShouldKeepOriginalOrder() {
        UnifiedElement a = new UnifiedElement();
        a.setId("a");
        a.setType(UnifiedElementType.PARAGRAPH.name());
        UnifiedElement b = new UnifiedElement();
        b.setId("b");
        b.setType(UnifiedElementType.PARAGRAPH.name());

        List<UnifiedElement> ordered = resolver.resolve(List.of(b, a), context());

        assertEquals(List.of("b", "a"), ordered.stream().map(UnifiedElement::getId).toList());
    }

    @Test
    void multiplePagesShouldKeepPageOrderAndSortInsideEachPage() {
        // 第 2 页两段传入顺序颠倒：仍按页内自上而下重排，且页序保持 1 → 2
        UnifiedElement p1 = element("p1", 1, 72, 100, 400, 20);
        UnifiedElement p2 = element("p2", 1, 72, 200, 400, 20);
        UnifiedElement q2 = element("q2", 2, 72, 300, 400, 20);
        UnifiedElement q1 = element("q1", 2, 72, 90, 400, 20);

        List<UnifiedElement> ordered = resolver.resolve(List.of(p1, q2, p2, q1), context());

        assertEquals(List.of("p1", "p2", "q1", "q2"),
                ordered.stream().map(UnifiedElement::getId).toList());
    }
}
