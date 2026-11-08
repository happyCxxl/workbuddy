package com.workbuddy.worker.parser.capability.impl;

import com.workbuddy.common.domain.parse.capability.LayoutResult;
import com.workbuddy.common.domain.parse.capability.PageImage;
import com.workbuddy.common.domain.parse.capability.ProviderContext;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 版面分析模型网关实现单测：字节缺失返回 null、区域映射与像素→pt 换算、形态错误归一（JAVA-TEST-011）。
 */
@ExtendWith(MockitoExtension.class)
class ModelGatewayStructuredLayoutProviderTest {

    @Mock
    private ModelGatewayClient client;

    private ModelGatewayStructuredLayoutProvider provider;

    @BeforeEach
    void setUp() {
        ParseProperties properties = new ParseProperties();
        properties.setPageImageDpi(144);
        provider = new ModelGatewayStructuredLayoutProvider(client, properties);
    }

    /**
     * 页图字节缺失：返回 null，不发模型调用。
     * <p>守护 {@link ModelGatewayStructuredLayoutProvider#analyze} 的缺字节短路；改为空调用网关时失败。
     */
    @Test
    void analyzeWithoutBytesReturnsNull() {
        PageImage page = new PageImage();
        page.setPage(2);
        page.setImageRef("page-2-layout");

        LayoutResult result = provider.analyze(page, new ProviderContext());

        assertNull(result);
        verifyNoInteractions(client);
    }

    /**
     * 正常响应：区域类型与置信度原样，bbox 像素→pt（dpi 144 → ÷2），阅读顺序按输出序，模型名取 meta。
     * <p>守护 {@link ModelGatewayStructuredLayoutProvider#analyze} 的映射约定；区域顺序、bbox 换算或模型名取值变化时失败。
     */
    @Test
    void analyzeMapsRegionsAndConvertsPixelsToPt() {
        PageImage page = new PageImage();
        page.setPage(2);
        page.setImageRef("page-2-layout");
        ProviderContext ctx = new ProviderContext();
        ctx.setTaskId(7L);
        ctx.setAssetBytes(ref -> new byte[] {1});
        OcrResponse response = new OcrResponse("原文", Map.of("layout_areas", List.of(
                Map.of("type", "title", "bbox", List.of(0, 0, 288, 72), "confidence", 0.9),
                Map.of("type", "text", "bbox", List.of(0, 72, 288, 288)))),
                1, Map.of("model_key", "qwen-vl-ocr-2025-11-20"));
        when(client.layoutAreas(anyString(), anyString(), any(), anyString(), anyString(), any(), anyString()))
                .thenReturn(response);

        LayoutResult result = provider.analyze(page, ctx);

        assertEquals(2, result.getRegions().size());
        assertEquals("title", result.getRegions().get(0).getType());
        assertEquals(144.0, result.getRegions().get(0).getBbox().getWidth(), 0.0001);
        assertEquals(36.0, result.getRegions().get(0).getBbox().getHeight(), 0.0001);
        assertEquals(0.9, result.getRegions().get(0).getConfidence(), 0.0001);
        assertEquals(List.of(0, 1), result.getOrder());
        assertEquals("qwen-vl-ocr-2025-11-20", result.getModel());
        verify(client).layoutAreas(eq("parse-layout-7-p2"), eq("parse.layout"), any(), eq("image/png"),
                eq("layout_analyze"), any(), eq("layout"));
    }

    /**
     * 响应缺 layout_areas：按 MODEL_SERVER_ERROR 抛出。
     * <p>守护错误码 {@code MODEL_SERVER_ERROR}；缺 layout_areas 不再拒绝时失败。
     */
    @Test
    void analyzeFailsWhenLayoutAreasMissing() {
        PageImage page = new PageImage();
        page.setPage(2);
        page.setImageRef("page-2-layout");
        ProviderContext ctx = new ProviderContext();
        ctx.setTaskId(7L);
        ctx.setAssetBytes(ref -> new byte[] {1});
        when(client.layoutAreas(anyString(), anyString(), any(), anyString(), anyString(), any(), anyString()))
                .thenReturn(new OcrResponse("原文", Map.of(), 1, Map.of()));

        WorkbuddyException error = assertThrows(WorkbuddyException.class,
                () -> provider.analyze(page, ctx));

        assertEquals(ErrorCode.MODEL_SERVER_ERROR.getCode(), error.getCode());
    }
}
