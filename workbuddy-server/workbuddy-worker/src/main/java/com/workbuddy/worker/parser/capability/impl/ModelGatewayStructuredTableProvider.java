package com.workbuddy.worker.parser.capability.impl;

import com.workbuddy.common.domain.parse.capability.ProviderContext;
import com.workbuddy.common.domain.parse.capability.TableCell;
import com.workbuddy.common.domain.parse.capability.TableRegion;
import com.workbuddy.common.domain.parse.capability.TableResult;
import com.workbuddy.common.enums.modelgateway.ModelScenarioKey;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.infra.modelgateway.ModelGatewayClient;
import com.workbuddy.infra.modelgateway.OcrResponse;
import com.workbuddy.worker.parser.ParseProperties;
import com.workbuddy.worker.parser.capability.TableStructureProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 表格结构识别的模型网关实现：取区域裁剪图字节 → 网关按业务键调用（table_structure/table）→
 * 首个表格的行列与单元格映射为 TableResult（格 bbox 像素→pt，相对裁剪图左上）。提示词与 schema 文案在灵犀统一管理。
 */
@Component
public class ModelGatewayStructuredTableProvider extends ModelGatewayStructuredProviderSupport<TableResult>
        implements TableStructureProvider {

    private static final ModelScenarioKey SCENARIO = ModelScenarioKey.TABLE_STRUCTURE;

    /**
     * 构造表格结构提供方。
     *
     * @param modelGatewayClient 模型网关客户端
     * @param properties         解析配置
     */
    public ModelGatewayStructuredTableProvider(ModelGatewayClient modelGatewayClient, ParseProperties properties) {
        super(modelGatewayClient, properties);
    }

    /**
     * 识别表格结构：裁剪图字节缺失返回 null（取用方按规则降级）；模型响应形态错误按 MODEL_SERVER_ERROR 抛出。
     *
     * @param region 表格区域
     * @param ctx    调用上下文
     * @return 表格结构结果
     */
    @Override
    public TableResult recognize(TableRegion region, ProviderContext ctx) {
        return super.recognize(ctx, region.getImageRef(), region.getPage());
    }

    @Override
    String requestIdPrefix() {
        return "parse-table-";
    }

    @Override
    OcrResponse invokeGateway(String requestId, byte[] image) {
        return modelGatewayClient.ocrLines(requestId, "parse.table", image, "image/png",
                SCENARIO.promptKey(), Map.of(), SCENARIO.schemaKey(), 1);
    }

    /** 网关直译 → TableResult：取首个表格，行列、跨度、文本与表头标记原样，格 bbox 像素→pt。 */
    @Override
    TableResult mapResponse(OcrResponse response, double pixelsPerPt) {
        Object rawTables = NullUtil.isNull(response.fields()) ? null : response.fields().get("tables");
        List<?> tables = rawTables instanceof List<?> list ? list : null;
        ThrowUtil.throwIf(NullUtil.isNull(tables) || tables.isEmpty(), ErrorCode.MODEL_SERVER_ERROR,
                "表格响应缺少 tables");
        Map<?, ?> table = tables.getFirst() instanceof Map<?, ?> map ? map : null;
        ThrowUtil.throwIf(NullUtil.isNull(table), ErrorCode.MODEL_SERVER_ERROR, "表格格式错误");
        Integer rows = table.get("rows") instanceof Number n ? n.intValue() : null;
        Integer cols = table.get("cols") instanceof Number n ? n.intValue() : null;
        List<?> rawCells = table.get("cells") instanceof List<?> list ? list : null;
        ThrowUtil.throwIf(NullUtil.isNull(rows) || NullUtil.isNull(cols) || NullUtil.isNull(rawCells),
                ErrorCode.MODEL_SERVER_ERROR, "表格响应缺少 rows/cols/cells");
        List<TableCell> cells = new ArrayList<>(rawCells.size());
        for (Object rawCell : rawCells) {
            Map<?, ?> cell = rawCell instanceof Map<?, ?> map ? map : null;
            ThrowUtil.throwIf(NullUtil.isNull(cell), ErrorCode.MODEL_SERVER_ERROR, "表格单元格格式错误");
            Integer row = cell.get("row") instanceof Number n ? n.intValue() : null;
            Integer col = cell.get("col") instanceof Number n ? n.intValue() : null;
            String text = cell.get("text") instanceof String s ? s : null;
            ThrowUtil.throwIf(NullUtil.isNull(row) || NullUtil.isNull(col) || NullUtil.isNull(text),
                    ErrorCode.MODEL_SERVER_ERROR, "表格单元格缺少 row/col/text");
            TableCell result = new TableCell();
            result.setRow(row);
            result.setCol(col);
            result.setText(text);
            if (cell.get("rowSpan") instanceof Number span) {
                result.setRowSpan(span.intValue());
            }
            if (cell.get("colSpan") instanceof Number span) {
                result.setColSpan(span.intValue());
            }
            if (cell.get("isHeader") instanceof Boolean header) {
                result.setIsHeader(header);
            }
            if (cell.get("confidence") instanceof Number confidence) {
                result.setConfidence(confidence.doubleValue());
            }
            List<?> box = cell.get("bbox") instanceof List<?> list ? list : null;
            if (ModelResponseSupport.hasBbox(box)) {
                result.setBbox(ModelResponseSupport.bbox(box, pixelsPerPt, "表格单元格 bbox 非法"));
            }
            cells.add(result);
        }
        TableResult result = new TableResult();
        result.setRows(rows);
        result.setCols(cols);
        result.setCells(cells);
        String model = modelKey(response);
        if (!NullUtil.isNull(model)) {
            result.setModel(model);
        }
        return result;
    }
}
