package com.workbuddy.worker.parser.capability.impl;

import com.workbuddy.common.domain.model.OutputFormat;
import com.workbuddy.common.domain.model.VisionRequest;
import com.workbuddy.common.domain.model.catalog.ModelCapability;
import com.workbuddy.common.domain.parse.BBox;
import com.workbuddy.common.domain.parse.capability.OcrRegion;
import com.workbuddy.common.domain.parse.capability.ProviderContext;
import com.workbuddy.common.domain.parse.capability.StructuredOcrResult;
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
 * 结构化 OCR 能力单测（桩网关）：开关关闭或区域为空即不发起调用；调用时带提示词与 JSON_SCHEMA 输出约束、
 * 候选按 OCR 能力筛；响应按契约映射回 StructuredOcrResult（逐行文本 + 包围盒 + 置信度 + 模型标识）。
 *
 * @author cxxl
 */
class VisionOcrProviderTest {

    /** 契约响应：两行识别结果 */
    private static final String OCR_RESPONSE = "{\"lines\":["
            + "{\"text\":\"第一章\",\"bbox\":{\"x\":72,\"y\":100,\"width\":200,\"height\":18},\"confidence\":0.98},"
            + "{\"text\":\"概述\",\"bbox\":{\"x\":72,\"y\":130,\"width\":120,\"height\":18}}]}";

    @Test
    void recognizeShouldMapResponseToStructuredOcrResult() {
        StubModelGateway gateway = new StubModelGateway().respondWith(OCR_RESPONSE, "qwen-vl-ocr", null);
        VisionOcrProvider provider = provider(enabledOcr(), gateway);

        StructuredOcrResult result = provider.recognize(region(), new ProviderContext());

        assertNotNull(result);
        assertEquals(2, result.getLines().size());
        assertEquals("第一章", result.getLines().getFirst().getText());
        assertEquals(72.0D, result.getLines().getFirst().getBbox().getX());
        assertEquals(0.98D, result.getLines().getFirst().getConfidence());
        assertNull(result.getLines().get(1).getConfidence());
        assertEquals("qwen-vl-ocr", result.getModel());
        assertNull(result.getVersion(), "厂商侧不给版本时留空");
    }

    @Test
    void recognizeShouldAskOcrCapabilityWithPromptAndSchema() {
        StubModelGateway gateway = new StubModelGateway().respondWith(OCR_RESPONSE, "qwen-vl-ocr", null);
        VisionOcrProvider provider = provider(enabledOcr(), gateway);

        provider.recognize(region(), new ProviderContext());

        assertEquals(List.of(ModelCapability.OCR), gateway.capabilities());
        VisionRequest request = gateway.lastRequest();
        assertEquals(OutputFormat.JSON_SCHEMA, request.getOutput().getFormat());
        assertTrue(request.getOutput().getSchema().contains("\"lines\""), request.getOutput().getSchema());
        assertTrue(request.getPrompt().contains("页码 5"), request.getPrompt());
        assertTrue(request.getPrompt().contains("width=595"), request.getPrompt());
        assertEquals("page5-ocr", request.getImageRef());
        assertNull(request.getImageBytes(), "解析侧没有图像字节来源，该字段留空");
        assertEquals(5, request.getRegion().getPageNo().intValue());
        assertEquals(842.0D, request.getRegion().getY1());
    }

    @Test
    void disabledSwitchShouldNotCallGateway() {
        StubModelGateway gateway = new StubModelGateway().respondWith(OCR_RESPONSE, "qwen-vl-ocr", null);
        VisionOcrProvider provider = provider(new CapabilityProperties(), gateway);

        assertNull(provider.recognize(region(), new ProviderContext()));
        assertEquals(0, gateway.visionCount());
    }

    @Test
    void emptyRegionShouldNotCallGateway() {
        StubModelGateway gateway = new StubModelGateway().respondWith(OCR_RESPONSE, "qwen-vl-ocr", null);
        VisionOcrProvider provider = provider(enabledOcr(), gateway);

        assertNull(provider.recognize(null, new ProviderContext()));
        assertEquals(0, gateway.visionCount());
    }

    @Test
    void unusableResponseShouldReturnNull() {
        CapabilityProperties properties = enabledOcr();

        assertNull(provider(properties, new StubModelGateway().respondWith("{", "m", null))
                .recognize(region(), new ProviderContext()), "响应不是合法 JSON");
        assertNull(provider(properties, new StubModelGateway().respondWith("", "m", null))
                .recognize(region(), new ProviderContext()), "响应文本为空");
    }

    @Test
    void gatewayFailureShouldReturnNull() {
        StubModelGateway gateway = new StubModelGateway().failWith(ErrorCode.MODEL_VENDOR_UNKNOWN);

        assertNull(provider(enabledOcr(), gateway).recognize(region(), new ProviderContext()));
        assertEquals(1, gateway.visionCount());
    }

    /** OCR 开关打开的配置 */
    private CapabilityProperties enabledOcr() {
        CapabilityProperties properties = new CapabilityProperties();
        properties.setOcrEnabled(true);
        return properties;
    }

    private VisionOcrProvider provider(CapabilityProperties properties, StubModelGateway gateway) {
        return new VisionOcrProvider(properties, gateway);
    }

    /** 待识别区域：整页（页码 + 页包围盒 + 页内引用） */
    private OcrRegion region() {
        OcrRegion region = new OcrRegion();
        region.setImageRef("page5-ocr");
        region.setPage(5);
        region.setBbox(new BBox(0, 0, 595, 842));
        return region;
    }
}
