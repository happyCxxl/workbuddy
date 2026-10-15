package com.workbuddy.worker.parser.capability.impl;

import com.workbuddy.common.domain.model.OutputFormat;
import com.workbuddy.common.domain.model.VisionRequest;
import com.workbuddy.common.domain.model.catalog.ModelCapability;
import com.workbuddy.common.domain.parse.BBox;
import com.workbuddy.common.domain.parse.capability.ProviderContext;
import com.workbuddy.common.domain.parse.capability.TableCell;
import com.workbuddy.common.domain.parse.capability.TableRegion;
import com.workbuddy.common.domain.parse.capability.TableResult;
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
 * 表格结构能力单测（桩网关）：开关关闭或区域为空即不发起调用；调用时带提示词与 JSON_SCHEMA 输出约束、
 * 候选按表格结构能力筛；响应按契约映射回 TableResult（含模型名与版本回填），失败一律返回 null。
 *
 * @author cxxl
 */
class VisionTableStructureProviderTest {

    /** 契约响应：2×2 表，首格横向合并且带表头标记与置信度 */
    private static final String TABLE_RESPONSE = "{\"rows\":2,\"cols\":2,\"confidence\":0.93,"
            + "\"cells\":[{\"row\":0,\"col\":0,\"rowSpan\":1,\"colSpan\":2,\"text\":\"合计\","
            + "\"isHeader\":true,\"confidence\":0.9},{\"row\":1,\"col\":0,\"text\":\"甲\"},"
            + "{\"row\":1,\"col\":1,\"text\":\"乙\"}]}";

    @Test
    void recognizeShouldMapResponseToTableResult() {
        StubModelGateway gateway = new StubModelGateway().respondWith(TABLE_RESPONSE, "qwen-vl-max", "2026-01");
        VisionTableStructureProvider provider = provider(enabledTable(), gateway);

        TableResult result = provider.recognize(region(), new ProviderContext());

        assertNotNull(result);
        assertEquals(2, result.getRows());
        assertEquals(2, result.getCols());
        assertEquals(0.93D, result.getConfidence());
        assertEquals(3, result.getCells().size());
        TableCell header = result.getCells().getFirst();
        assertEquals(0, header.getRow());
        assertEquals(2, header.getColSpan());
        assertEquals("合计", header.getText());
        assertTrue(header.getIsHeader());
        assertEquals(0.9D, header.getConfidence());
        assertEquals("qwen-vl-max", result.getModel());
        assertEquals("2026-01", result.getVersion());
    }
    @Test
    void recognizeShouldAskTableStructureCapabilityWithPromptAndSchema() {
        StubModelGateway gateway = new StubModelGateway().respondWith(TABLE_RESPONSE, "qwen-vl-max", "2026-01");
        VisionTableStructureProvider provider = provider(enabledTable(), gateway);

        provider.recognize(region(), new ProviderContext());

        assertEquals(List.of(ModelCapability.TABLE_STRUCTURE), gateway.capabilities());
        VisionRequest request = gateway.lastRequest();
        assertEquals(OutputFormat.JSON_SCHEMA, request.getOutput().getFormat());
        assertTrue(request.getOutput().getSchema().contains("\"cells\""), request.getOutput().getSchema());
        assertTrue(request.getPrompt().contains("页码 7"), request.getPrompt());
        assertTrue(request.getPrompt().contains("Alpha 1"), request.getPrompt());
        assertEquals("page7-table-region", request.getImageRef());
        assertNull(request.getImageBytes(), "解析侧没有图像字节来源，该字段留空");
        assertEquals(7, request.getRegion().getPageNo().intValue());
        assertEquals(72.0D, request.getRegion().getX0());
        assertEquals(100.0D, request.getRegion().getY0());
        assertEquals(372.0D, request.getRegion().getX1());
        assertEquals(140.0D, request.getRegion().getY1());
    }

    @Test
    void disabledSwitchShouldNotCallGateway() {
        StubModelGateway gateway = new StubModelGateway().respondWith(TABLE_RESPONSE, "qwen-vl-max", "2026-01");
        VisionTableStructureProvider provider = provider(new CapabilityProperties(), gateway);

        assertNull(provider.recognize(region(), new ProviderContext()));
        assertEquals(0, gateway.visionCount());
    }

    @Test
    void emptyRegionShouldNotCallGateway() {
        StubModelGateway gateway = new StubModelGateway().respondWith(TABLE_RESPONSE, "qwen-vl-max", "2026-01");
        VisionTableStructureProvider provider = provider(enabledTable(), gateway);

        assertNull(provider.recognize(null, new ProviderContext()));
        assertEquals(0, gateway.visionCount());
    }

    @Test
    void unusableResponseShouldReturnNull() {
        CapabilityProperties properties = enabledTable();

        assertNull(provider(properties, new StubModelGateway().respondWith("{", "m", "v"))
                .recognize(region(), new ProviderContext()), "响应不是合法 JSON");
        assertNull(provider(properties, new StubModelGateway().respondWith("", "m", "v"))
                .recognize(region(), new ProviderContext()), "响应文本为空");
    }

    @Test
    void gatewayFailureShouldReturnNull() {
        StubModelGateway gateway = new StubModelGateway().failWith(ErrorCode.MODEL_UNREACHABLE);

        assertNull(provider(enabledTable(), gateway).recognize(region(), new ProviderContext()));
        assertEquals(1, gateway.visionCount());
    }

    /** 表格结构开关打开的配置 */
    private CapabilityProperties enabledTable() {
        CapabilityProperties properties = new CapabilityProperties();
        properties.setTableEnabled(true);
        return properties;
    }

    private VisionTableStructureProvider provider(CapabilityProperties properties, StubModelGateway gateway) {
        return new VisionTableStructureProvider(properties, gateway);
    }

    /** 请求区域：页码 + 包围盒 + 块内文本 */
    private TableRegion region() {
        TableRegion region = new TableRegion();
        region.setImageRef("page7-table-region");
        region.setPage(7);
        region.setBbox(new BBox(72, 100, 300, 40));
        region.setOcrText("Alpha 1\nBeta 2");
        return region;
    }
}
