package com.workbuddy.worker.parser.capability.impl;

import com.workbuddy.common.domain.parse.capability.OcrLine;
import com.workbuddy.common.domain.parse.capability.OcrRegion;
import com.workbuddy.common.domain.parse.capability.ProviderContext;
import com.workbuddy.common.domain.parse.capability.StructuredOcrResult;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.WorkbuddyException;
import com.workbuddy.infra.modelgateway.ModelGatewayClient;
import com.workbuddy.infra.modelgateway.OcrResponse;
import com.workbuddy.worker.parser.ParseProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 结构化 OCR 模型网关实现单测：字节缺失返回 null、字段映射与像素→pt 换算、模型名回填、形态错误归一。
 */
@ExtendWith(MockitoExtension.class)
class ModelGatewayStructuredOcrProviderTest {

    @Mock
    private ModelGatewayClient client;

    private ModelGatewayStructuredOcrProvider provider;

    @BeforeEach
    void setUp() {
        ParseProperties properties = new ParseProperties();
        properties.setPageImageDpi(144);
        provider = new ModelGatewayStructuredOcrProvider(client, properties);
    }

    /**
     * 页图字节缺失：返回 null，不发模型调用。
     */
    @Test
    void recognizeWithoutBytesReturnsNull() {
        OcrRegion region = new OcrRegion();
        region.setPage(2);
        region.setImageRef("asset-1");

        StructuredOcrResult result = provider.recognize(region, new ProviderContext());

        assertNull(result);
        verifyNoInteractions(client);
    }

    /**
     * 正常响应：行文本与置信度原样，bbox 像素→pt（dpi 144 → ÷2），模型名取 meta.model_key。
     */
    @Test
    void recognizeMapsLinesAndConvertsPixelsToPt() {
        OcrRegion region = new OcrRegion();
        region.setPage(2);
        region.setImageRef("asset-1");
        ProviderContext ctx = new ProviderContext();
        ctx.setTaskId(7L);
        ctx.setAssetBytes(ref -> new byte[] {1});
        OcrResponse response = new OcrResponse("原文", Map.of("text_lines", List.of(
                Map.of("text", "行一", "bbox", List.of(0, 0, 144, 72), "confidence", 0.9))),
                1, Map.of("model_key", "qwen-vl-ocr-2025-11-20"));
        when(client.ocrLines(anyString(), anyString(), any(), anyString(), anyString(), any(), any(), anyInt()))
                .thenReturn(response);

        StructuredOcrResult result = provider.recognize(region, ctx);

        assertEquals(1, result.getLines().size());
        OcrLine line = result.getLines().getFirst();
        assertEquals("行一", line.getText());
        assertEquals(0.0, line.getBbox().getX(), 0.0001);
        assertEquals(0.0, line.getBbox().getY(), 0.0001);
        assertEquals(72.0, line.getBbox().getWidth(), 0.0001);
        assertEquals(36.0, line.getBbox().getHeight(), 0.0001);
        assertEquals(0.9, line.getConfidence(), 0.0001);
        assertEquals("qwen-vl-ocr-2025-11-20", result.getModel());
        verify(client).ocrLines(eq("parse-ocr-7-p2"), eq("parse.ocr"), any(), eq("image/png"),
                eq("ocr_lines"), any(), eq("ocr_lines"), eq(1));
    }

    /**
     * bbox 五元素（模型把置信度塞尾部）：前四项作包围盒，值域内尾项作置信度。
     */
    @Test
    void recognizeUsesBboxTailAsConfidence() {
        OcrRegion region = new OcrRegion();
        region.setPage(2);
        region.setImageRef("asset-1");
        ProviderContext ctx = new ProviderContext();
        ctx.setTaskId(7L);
        ctx.setAssetBytes(ref -> new byte[] {1});
        OcrResponse response = new OcrResponse("原文", Map.of("text_lines", List.of(
                Map.of("text", "行一", "bbox", List.of(0, 0, 144, 72, 0.9)))),
                1, Map.of("model_key", "qwen-vl-ocr-2025-11-20"));
        when(client.ocrLines(anyString(), anyString(), any(), anyString(), anyString(), any(), any(), anyInt()))
                .thenReturn(response);

        StructuredOcrResult result = provider.recognize(region, ctx);

        OcrLine line = result.getLines().getFirst();
        assertEquals(72.0, line.getBbox().getWidth(), 0.0001);
        assertEquals(36.0, line.getBbox().getHeight(), 0.0001);
        assertEquals(0.9, line.getConfidence(), 0.0001);
    }

    /**
     * 响应缺 text_lines：按 MODEL_SERVER_ERROR 抛出。
     */
    @Test
    void recognizeFailsWhenTextLinesMissing() {
        OcrRegion region = new OcrRegion();
        region.setPage(2);
        region.setImageRef("asset-1");
        ProviderContext ctx = new ProviderContext();
        ctx.setTaskId(7L);
        ctx.setAssetBytes(ref -> new byte[] {1});
        when(client.ocrLines(anyString(), anyString(), any(), anyString(), anyString(), any(), any(), anyInt()))
                .thenReturn(new OcrResponse("原文", Map.of(), 1, Map.of()));

        WorkbuddyException error = assertThrows(WorkbuddyException.class,
                () -> provider.recognize(region, ctx));

        assertEquals(ErrorCode.MODEL_SERVER_ERROR.getCode(), error.getCode());
    }
}
