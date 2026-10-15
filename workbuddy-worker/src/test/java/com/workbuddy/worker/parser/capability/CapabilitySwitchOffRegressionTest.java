package com.workbuddy.worker.parser.capability;

import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.capability.PageImage;
import com.workbuddy.common.domain.parse.capability.TableRegion;
import com.workbuddy.common.domain.parse.signal.ParseFact;
import com.workbuddy.common.enums.parse.ElementType;
import com.workbuddy.worker.parser.capability.impl.CapabilityRegistryImpl;
import com.workbuddy.worker.parser.capability.impl.VisionLayoutProvider;
import com.workbuddy.worker.parser.capability.impl.VisionOcrProvider;
import com.workbuddy.worker.parser.capability.impl.VisionTableStructureProvider;
import com.workbuddy.worker.parser.capability.support.StubModelGateway;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 开关关闭时的逐字段回归单测：三项开关默认关，注册表给出的候选为 null，表格与版面两次能力尝试
 * 都不发起网关调用、只落一条降级事实（区域、类型、证据文案逐项固定），表格侧无表格元素产出。
 * 图表能力（表格 / 版面 / OCR）三项在开关关闭时的取值见 {@link #registryShouldYieldNoCandidate()}。
 *
 * @author cxxl
 */
class CapabilitySwitchOffRegressionTest {

    /** 契约响应：桩网关给出可用结构（开关关闭时通道不可达，这些取值不该被用到） */
    private static final String TABLE_RESPONSE = "{\"rows\":9,\"cols\":9,\"cells\":[{\"row\":0,\"col\":0,\"text\":\"x\"}]}";

    /** 开关关闭：注册表三个口都取不到候选 */
    @Test
    void registryShouldYieldNoCandidate() {
        StubModelGateway gateway = gateway();
        CapabilityRegistry registry = registry(new CapabilityProperties(), gateway);

        assertNull(registry.ocr());
        assertNull(registry.layout());
        assertNull(registry.table());
        assertEquals(0, gateway.visionCount());
    }

    /** 表格能力尝试：无表格元素，降级事实挂 TABLE 类型、证据为「未接入能力」 */
    @Test
    void tableAttemptShouldKeepDegradedFactFields() {
        StubModelGateway gateway = gateway();
        TableRegion region = new TableRegion();
        region.setPage(2);
        region.setOcrText("Alpha 1\nBeta 2");

        TableCapabilityAttempt.Outcome outcome =
                TableCapabilityAttempt.attempt(registry(new CapabilityProperties(), gateway), region, 2);

        assertFalse(outcome.enhanced());
        assertNull(outcome.table());
        assertNull(outcome.usage());
        assertEquals("page 2", outcome.fact().getRegion());
        assertEquals("TABLE", outcome.fact().getType().name());
        assertEquals("表格能力未产出结构，已按内置规则降级（未接入能力）", outcome.fact().getEvidence());
        assertEquals(0, gateway.visionCount());
    }

    /** 版面能力尝试：无顺序，降级事实挂 LAYOUT 类型、证据为「未接入能力」 */
    @Test
    void layoutAttemptShouldKeepDegradedFactFields() {
        StubModelGateway gateway = gateway();
        PageImage page = new PageImage();
        page.setPage(4);

        LayoutCapabilityAttempt.Outcome outcome =
                LayoutCapabilityAttempt.attempt(registry(new CapabilityProperties(), gateway), page, 4);

        assertFalse(outcome.enhanced());
        assertNull(outcome.order());
        assertNull(outcome.regions());
        assertNull(outcome.usage());
        assertEquals("page 4", outcome.fact().getRegion());
        assertEquals("LAYOUT", outcome.fact().getType().name());
        assertEquals("版面能力未产出顺序，已按内置几何口径输出（未接入能力）", outcome.fact().getEvidence());
        assertEquals(0, gateway.visionCount());
    }

    /** OCR 路：注册表取不到实现，降级事实的证据文案与区域逐项固定 */
    @Test
    void ocrAttemptShouldKeepDegradedFactFields() {
        StubModelGateway gateway = gateway();
        StructuredOcrProvider provider = registry(new CapabilityProperties(), gateway).ocr();

        assertNull(provider);
        ParseFact fact = CapabilityFacts.ocrDegraded("page 6", "未接入能力");
        assertEquals("page 6", fact.getRegion());
        assertEquals("OCR_TEXT", fact.getType().name());
        assertEquals("文字能力未补出内容（未接入能力）", fact.getEvidence());
        assertEquals(0, gateway.visionCount());
    }

    /** 开关关闭时表格侧彻底不产元素：注册表候选为空即没有可调对象 */
    @Test
    void tableAttemptShouldYieldNoElementWithSwitchOff() {
        TableCapabilityAttempt.Outcome outcome = TableCapabilityAttempt.attempt(
                registry(new CapabilityProperties(), gateway()), null, 1);

        assertFalse(outcome.enhanced());
        assertNull(outcome.table());
        assertNull(outcome.usage());
    }

    /** 表格元素类型口径：规则路径产 TABLE 元素这一点不因能力接线改变 */
    @Test
    void rulePathTableElementTypeShouldStayTable() {
        ParseElement element = ParseElement.of(ElementType.TABLE);

        assertEquals("TABLE", element.getType());
    }

    /** 桩网关：给出可用结构（开关关闭时不该被调用） */
    private StubModelGateway gateway() {
        return new StubModelGateway().respondWith(TABLE_RESPONSE, "stub", "1");
    }

    /** 按生产装配口径接线：三个真实实现 + 开关 */
    private CapabilityRegistry registry(CapabilityProperties properties, StubModelGateway gateway) {
        return new CapabilityRegistryImpl(properties,
                List.of(new VisionOcrProvider(properties, gateway)),
                List.of(new VisionLayoutProvider(properties, gateway)),
                List.of(new VisionTableStructureProvider(properties, gateway)));
    }
}
