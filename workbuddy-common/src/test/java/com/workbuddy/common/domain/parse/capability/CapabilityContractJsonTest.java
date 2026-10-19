package com.workbuddy.common.domain.parse.capability;

import com.workbuddy.common.domain.parse.BBox;
import com.workbuddy.common.utils.JsonUtil;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 能力契约 JSON 单测：模型按契约给出的表格结构能直接映射到 TableRegion / TableResult / TableCell，
 * 字段名与格级 bbox / 置信度是契约的一部分，改动即测试失败。
 */
class CapabilityContractJsonTest {

    /** 请求区域：页码、包围盒与可选 OCR 文本（{@code regionShouldRoundTripWithPageAndBBox}）。 */
    @Test
    void regionShouldRoundTripWithPageAndBBox() {
        TableRegion region = new TableRegion();
        region.setImageRef("page1-table0");
        region.setPage(3);
        region.setBbox(new BBox(72, 100, 300, 120));
        region.setOcrText("项目 分值");

        TableRegion parsed = JsonUtil.toObject(JsonUtil.toJsonStr(region), TableRegion.class);

        assertNotNull(parsed);
        assertEquals("page1-table0", parsed.getImageRef());
        assertEquals(3, parsed.getPage());
        assertEquals(72.0, parsed.getBbox().getX(), 0.0001);
        assertEquals(120.0, parsed.getBbox().getHeight(), 0.0001);
        assertEquals("项目 分值", parsed.getOcrText());
    }

    /** 响应体：grid + 每格 row/col span + bbox + 置信度 + 模型标识（{@code tableResultShouldMapGridSpansBBoxAndConfidence}）。 */
    @Test
    void tableResultShouldMapGridSpansBBoxAndConfidence() {
        String json = "{\"rows\":2,\"cols\":2,\"confidence\":0.93,\"crossPage\":false,"
                + "\"model\":\"tableformer\",\"version\":\"1.0\",\"cells\":["
                + "{\"row\":0,\"col\":0,\"rowSpan\":1,\"colSpan\":2,\"text\":\"合计\",\"isHeader\":true,"
                + "\"bbox\":{\"x\":72.0,\"y\":100.0,\"width\":300.0,\"height\":20.0},\"confidence\":0.97},"
                + "{\"row\":1,\"col\":0,\"text\":\"A1\",\"bbox\":{\"x\":72.0,\"y\":120.0,\"width\":150.0,"
                + "\"height\":20.0}}]}";

        TableResult result = JsonUtil.toObject(json, TableResult.class);

        assertNotNull(result);
        assertEquals(2, result.getRows());
        assertEquals(2, result.getCols());
        assertEquals(0.93, result.getConfidence(), 0.0001);
        assertEquals("tableformer", result.getModel());
        assertEquals("1.0", result.getVersion());

        List<TableCell> cells = result.getCells();
        assertEquals(2, cells.size());
        TableCell merged = cells.getFirst();
        assertEquals(2, merged.getColSpan());
        assertEquals("合计", merged.getText());
        assertEquals(0.97, merged.getConfidence(), 0.0001);
        assertEquals(300.0, merged.getBbox().getWidth(), 0.0001);
        // 模型没给的字段保持 null（下游按缺省口径回落，例如置信度缺省按 1 处理）
        assertEquals(null, cells.get(1).getIsHeader());
        assertEquals(null, cells.get(1).getConfidence());
    }
}
