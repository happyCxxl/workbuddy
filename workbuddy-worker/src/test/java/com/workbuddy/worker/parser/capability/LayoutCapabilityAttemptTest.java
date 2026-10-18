package com.workbuddy.worker.parser.capability;

import com.workbuddy.common.domain.parse.BBox;
import com.workbuddy.common.domain.parse.capability.CapabilityUsage;
import com.workbuddy.common.domain.parse.capability.LayoutRegion;
import com.workbuddy.common.domain.parse.capability.LayoutResult;
import com.workbuddy.common.domain.parse.capability.PageImage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 版面能力尝试单测（测试桩 provider，不走 HTTP）：拿到区域与顺序时给出补强事实与能力引用；
 * 未接入 / 页面为空 / 结果不完整三种情况都只给降级留痕。
 */
class LayoutCapabilityAttemptTest {

    /** 桩 provider 给出两个区域与顺序 → 顺序可用、补强事实带模型与区域数、能力引用带版本 */
    @Test
    void stubProviderShouldYieldOrderAndEnhancedFact() {
        LayoutResult result = new LayoutResult();
        result.setModel("stub-layout");
        result.setVersion("2.1");
        result.setOrder(List.of(1, 0));
        result.setRegions(List.of(region("title"), region("text")));

        LayoutCapabilityAttempt.Outcome outcome =
                LayoutCapabilityAttempt.attempt(registry(result), page(), 5);

        assertTrue(outcome.enhanced());
        assertEquals(List.of(1, 0), outcome.order());
        assertEquals(2, outcome.regions().size());
        assertEquals("stub-layout 2.1", outcome.usage().model() + " " + outcome.usage().version());
        assertEquals(CapabilityUsage.KIND_LAYOUT, outcome.usage().kind());
        assertTrue(outcome.fact().getEvidence().contains("能力补强"), () -> outcome.fact().getEvidence());
        assertTrue(outcome.fact().getEvidence().contains("2 个区域"), () -> outcome.fact().getEvidence());
    }

    /** 未接入能力：无顺序、降级留痕，事实类型为 LAYOUT */
    @Test
    void missingRegistryShouldYieldDegradedFactOnly() {
        LayoutCapabilityAttempt.Outcome outcome = LayoutCapabilityAttempt.attempt(null, page(), 1);

        assertFalse(outcome.enhanced());
        assertNull(outcome.order());
        assertNull(outcome.usage());
        assertEquals("LAYOUT", outcome.fact().getType().name());
        assertTrue(outcome.fact().getEvidence().contains("未接入能力"), () -> outcome.fact().getEvidence());
    }

    /** 页面为空：降级留痕点名原因 */
    @Test
    void emptyPageShouldYieldDegradedFact() {
        LayoutCapabilityAttempt.Outcome outcome =
                LayoutCapabilityAttempt.attempt(registry(new LayoutResult()), null, 2);

        assertFalse(outcome.enhanced());
        assertTrue(outcome.fact().getEvidence().contains("页面为空"), () -> outcome.fact().getEvidence());
    }

    /** 桩 provider 返回只有区域没有顺序：不可用，降级原因写「调用失败或结果不完整」 */
    @Test
    void unorderedResultShouldYieldDegradedFact() {
        LayoutResult withoutOrder = new LayoutResult();
        withoutOrder.setRegions(List.of(region("text")));

        LayoutCapabilityAttempt.Outcome outcome =
                LayoutCapabilityAttempt.attempt(registry(withoutOrder), page(), 3);

        assertFalse(outcome.enhanced());
        assertTrue(outcome.fact().getEvidence().contains("调用失败或结果不完整"),
                () -> outcome.fact().getEvidence());
    }

    private LayoutRegion region(String type) {
        LayoutRegion region = new LayoutRegion();
        region.setType(type);
        region.setBbox(new BBox(72, 100, 300, 40));
        region.setConfidence(0.9);
        return region;
    }

    private PageImage page() {
        PageImage image = new PageImage();
        image.setPage(1);
        image.setDpi(150);
        return image;
    }

    /** 只接版面能力的注册表（其余两项返回 null） */
    private CapabilityRegistry registry(LayoutResult result) {
        return new CapabilityRegistry() {
            @Override
            public StructuredOcrProvider ocr() {
                return null;
            }

            @Override
            public LayoutProvider layout() {
                return (page, ctx) -> result;
            }

            @Override
            public TableStructureProvider table() {
                return null;
            }
        };
    }
}
