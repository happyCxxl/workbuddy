package com.workbuddy.worker.parser.capability.impl;

import com.workbuddy.common.domain.model.OutputFormat;
import com.workbuddy.common.domain.model.VisionRequest;
import com.workbuddy.common.domain.model.catalog.ModelCapability;
import com.workbuddy.common.domain.parse.capability.LayoutResult;
import com.workbuddy.common.domain.parse.capability.PageImage;
import com.workbuddy.common.domain.parse.capability.ProviderContext;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.worker.parser.capability.CapabilityProperties;
import com.workbuddy.worker.parser.capability.support.StubModelGateway;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 版面能力单测（桩网关）：开关关闭或页面为空即不发起调用；调用时带提示词与 JSON_SCHEMA 输出约束、
 * 候选按版面能力筛；响应按契约映射回 LayoutResult（区域清单 + 阅读顺序 + 模型名与版本），失败返回 null。
 *
 * @author cxxl
 */
class VisionLayoutProviderTest {

    /** 契约响应：两个区域与阅读顺序 */
    private static final String LAYOUT_RESPONSE = "{\"regions\":["
            + "{\"type\":\"title\",\"bbox\":{\"x\":48,\"y\":20,\"width\":500,\"height\":40},\"confidence\":0.95},"
            + "{\"type\":\"text\",\"bbox\":{\"x\":48,\"y\":80,\"width\":500,\"height\":600}}],"
            + "\"order\":[1,0]}";

    @Test
    void analyzeShouldMapResponseToLayoutResult() {
        StubModelGateway gateway = new StubModelGateway().respondWith(LAYOUT_RESPONSE, "qwen-vl-max", "2026-01");
        VisionLayoutProvider provider = provider(enabledLayout(), gateway);

        LayoutResult result = provider.analyze(page(), new ProviderContext());

        assertNotNull(result);
        assertEquals(2, result.getRegions().size());
        assertEquals("title", result.getRegions().getFirst().getType());
        assertEquals(48.0D, result.getRegions().getFirst().getBbox().getX());
        assertEquals(0.95D, result.getRegions().getFirst().getConfidence());
        assertEquals(List.of(1, 0), result.getOrder());
        assertEquals("qwen-vl-max", result.getModel());
        assertEquals("2026-01", result.getVersion());
    }

    @Test
    void analyzeShouldAskLayoutCapabilityWithPromptAndSchema() {
        StubModelGateway gateway = new StubModelGateway().respondWith(LAYOUT_RESPONSE, "qwen-vl-max", "2026-01");
        VisionLayoutProvider provider = provider(enabledLayout(), gateway);

        provider.analyze(page(), new ProviderContext());

        assertEquals(List.of(ModelCapability.LAYOUT), gateway.capabilities());
        VisionRequest request = gateway.lastRequest();
        assertEquals(OutputFormat.JSON_SCHEMA, request.getOutput().getFormat());
        assertTrue(request.getOutput().getSchema().contains("\"order\""), request.getOutput().getSchema());
        assertTrue(request.getPrompt().contains("页码 3"), request.getPrompt());
        assertTrue(request.getPrompt().contains("图像 DPI 150"), request.getPrompt());
        assertNull(request.getRegion(), "整页分析不带区域");
        assertNull(request.getImageBytes(), "解析侧没有图像字节来源，该字段留空");
    }

    @Test
    void disabledSwitchShouldNotCallGateway() {
        StubModelGateway gateway = new StubModelGateway().respondWith(LAYOUT_RESPONSE, "qwen-vl-max", "2026-01");
        VisionLayoutProvider provider = provider(new CapabilityProperties(), gateway);

        assertNull(provider.analyze(page(), new ProviderContext()));
        assertEquals(0, gateway.visionCount());
    }

    @Test
    void emptyPageShouldNotCallGateway() {
        StubModelGateway gateway = new StubModelGateway().respondWith(LAYOUT_RESPONSE, "qwen-vl-max", "2026-01");
        VisionLayoutProvider provider = provider(enabledLayout(), gateway);

        assertNull(provider.analyze(null, new ProviderContext()));
        assertEquals(0, gateway.visionCount());
    }

    @Test
    void unusableResponseShouldReturnNull() {
        CapabilityProperties properties = enabledLayout();

        assertNull(provider(properties, new StubModelGateway().respondWith("{", "m", "v"))
                .analyze(page(), new ProviderContext()), "响应不是合法 JSON");
        assertNull(provider(properties, new StubModelGateway().respondWith("  ", "m", "v"))
                .analyze(page(), new ProviderContext()), "响应文本为空");
    }

    @Test
    void gatewayFailureShouldReturnNull() {
        StubModelGateway gateway = new StubModelGateway().failWith(ErrorCode.MODEL_TIMEOUT);

        assertNull(provider(enabledLayout(), gateway).analyze(page(), new ProviderContext()));
        assertEquals(1, gateway.visionCount());
    }

    /** 版面开关打开的配置 */
    private CapabilityProperties enabledLayout() {
        CapabilityProperties properties = new CapabilityProperties();
        properties.setLayoutEnabled(true);
        return properties;
    }

    private VisionLayoutProvider provider(CapabilityProperties properties, StubModelGateway gateway) {
        return new VisionLayoutProvider(properties, gateway);
    }

    /** 页面图像：页码 + 尺寸 + DPI */
    private PageImage page() {
        PageImage page = new PageImage();
        page.setPage(3);
        page.setWidth(595.0D);
        page.setHeight(842.0D);
        page.setDpi(150);
        return page;
    }
}
