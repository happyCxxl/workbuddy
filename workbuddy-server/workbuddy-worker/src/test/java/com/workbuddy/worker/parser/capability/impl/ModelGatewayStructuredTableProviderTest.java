package com.workbuddy.worker.parser.capability.impl;

import com.workbuddy.common.domain.parse.capability.ProviderContext;
import com.workbuddy.common.domain.parse.capability.TableRegion;
import com.workbuddy.common.domain.parse.capability.TableResult;
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
 * 表格结构识别模型网关实现单测：字节缺失返回 null、单元格映射与像素→pt 换算、形态错误归一。
 */
@ExtendWith(MockitoExtension.class)
class ModelGatewayStructuredTableProviderTest {

    @Mock
    private ModelGatewayClient client;

    private ModelGatewayStructuredTableProvider provider;

    @BeforeEach
    void setUp() {
        ParseProperties properties = new ParseProperties();
        properties.setPageImageDpi(144);
        provider = new ModelGatewayStructuredTableProvider(client, properties);
    }

    /**
     * 裁剪图字节缺失：返回 null，不发模型调用。
     */
    @Test
    void recognizeWithoutBytesReturnsNull() {
        TableRegion region = new TableRegion();
        region.setPage(2);
        region.setImageRef("page2-table-region");

        TableResult result = provider.recognize(region, new ProviderContext());

        assertNull(result);
        verifyNoInteractions(client);
    }

    /**
     * 正常响应：行列、跨度、文本与表头标记原样，格 bbox 像素→pt（dpi 144 → ÷2），模型名取 meta。
     */
    @Test
    void recognizeMapsCellsAndConvertsPixelsToPt() {
        TableRegion region = new TableRegion();
        region.setPage(2);
        region.setImageRef("page2-table-region");
        ProviderContext ctx = new ProviderContext();
        ctx.setTaskId(7L);
        ctx.setAssetBytes(ref -> new byte[] {1});
        OcrResponse response = new OcrResponse("原文", Map.of("tables", List.of(Map.of(
                "rows", 2, "cols", 2,
                "cells", List.of(
                        Map.of("row", 0, "col", 0, "rowSpan", 1, "colSpan", 2, "text", "合计",
                                "isHeader", true, "bbox", List.of(0, 0, 288, 72)),
                        Map.of("row", 1, "col", 0, "text", "30"))))),
                1, Map.of("model_key", "qwen-vl-ocr-2025-11-20"));
        when(client.ocrLines(anyString(), anyString(), any(), anyString(), anyString(), any(), anyString(),
                anyInt())).thenReturn(response);

        TableResult result = provider.recognize(region, ctx);

        assertEquals(2, result.getRows());
        assertEquals(2, result.getCols());
        assertEquals(2, result.getCells().size());
        assertEquals("合计", result.getCells().get(0).getText());
        assertEquals(2, result.getCells().get(0).getColSpan());
        assertEquals(Boolean.TRUE, result.getCells().get(0).getIsHeader());
        assertEquals(144.0, result.getCells().get(0).getBbox().getWidth(), 0.0001);
        assertEquals(36.0, result.getCells().get(0).getBbox().getHeight(), 0.0001);
        assertEquals("qwen-vl-ocr-2025-11-20", result.getModel());
        verify(client).ocrLines(eq("parse-table-7-p2"), eq("parse.table"), any(), eq("image/png"),
                eq("table_structure"), any(), eq("table"), eq(1));
    }

    /**
     * 响应缺 tables：按 MODEL_SERVER_ERROR 抛出。
     */
    @Test
    void recognizeFailsWhenTablesMissing() {
        TableRegion region = new TableRegion();
        region.setPage(2);
        region.setImageRef("page2-table-region");
        ProviderContext ctx = new ProviderContext();
        ctx.setTaskId(7L);
        ctx.setAssetBytes(ref -> new byte[] {1});
        when(client.ocrLines(anyString(), anyString(), any(), anyString(), anyString(), any(), anyString(),
                anyInt())).thenReturn(new OcrResponse("原文", Map.of(), 1, Map.of()));

        WorkbuddyException error = assertThrows(WorkbuddyException.class,
                () -> provider.recognize(region, ctx));

        assertEquals(ErrorCode.MODEL_SERVER_ERROR.getCode(), error.getCode());
    }
}
