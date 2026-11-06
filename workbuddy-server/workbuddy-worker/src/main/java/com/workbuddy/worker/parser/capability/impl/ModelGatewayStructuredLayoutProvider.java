package com.workbuddy.worker.parser.capability.impl;

import com.workbuddy.common.domain.parse.capability.LayoutRegion;
import com.workbuddy.common.domain.parse.capability.LayoutResult;
import com.workbuddy.common.domain.parse.capability.PageImage;
import com.workbuddy.common.domain.parse.capability.ProviderContext;
import com.workbuddy.common.enums.modelgateway.ModelScenarioKey;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.infra.modelgateway.ModelGatewayClient;
import com.workbuddy.infra.modelgateway.OcrResponse;
import com.workbuddy.worker.parser.ParseProperties;
import com.workbuddy.worker.parser.capability.LayoutProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 版面分析的模型网关实现：取页图字节 → 网关按业务键调用（layout_analyze/layout）→
 * layout_areas 映射为版面区域（bbox 像素→pt，阅读顺序按模型输出序）。提示词与 schema 文案在灵犀统一管理。
 */
@Component
@RequiredArgsConstructor
public class ModelGatewayStructuredLayoutProvider implements LayoutProvider {

    private static final ModelScenarioKey SCENARIO = ModelScenarioKey.LAYOUT_ANALYZE;

    private final ModelGatewayClient modelGatewayClient;
    private final ParseProperties properties;

    /**
     * 分析页面版面：页图字节缺失返回 null（取用方走几何口径）；模型响应形态错误按 MODEL_SERVER_ERROR 抛出。
     *
     * @param page 页面图像
     * @param ctx  调用上下文
     * @return 版面结果（区域分类 + 阅读顺序）
     */
    @Override
    public LayoutResult analyze(PageImage page, ProviderContext ctx) {
        byte[] image = CapabilityByteSupport.resolve(ctx, page.getImageRef());
        if (NullUtil.isNull(image)) {
            return null;
        }
        String requestId = "parse-layout-" + ctx.getTaskId() + "-p" + page.getPage();
        OcrResponse response = modelGatewayClient.ocrLines(requestId, "parse.layout", image, "image/png",
                SCENARIO.promptKey(), Map.of(), SCENARIO.schemaKey(), 1);
        return toResult(response);
    }

    /** 网关直译 → LayoutResult：区域类型与置信度原样，bbox 像素→pt（÷ dpi/72），顺序按模型输出序。 */
    private LayoutResult toResult(OcrResponse response) {
        Object rawAreas = NullUtil.isNull(response.fields()) ? null : response.fields().get("layout_areas");
        List<?> rows = rawAreas instanceof List<?> list ? list : null;
        ThrowUtil.throwIf(NullUtil.isNull(rows), ErrorCode.MODEL_SERVER_ERROR, "版面响应缺少 layout_areas");
        double pixelsPerPt = properties.getPageImageDpi() / 72.0;
        List<LayoutRegion> regions = new ArrayList<>(rows.size());
        List<Integer> order = new ArrayList<>(rows.size());
        for (int index = 0; index < rows.size(); index++) {
            Map<?, ?> row = rows.get(index) instanceof Map<?, ?> map ? map : null;
            ThrowUtil.throwIf(NullUtil.isNull(row), ErrorCode.MODEL_SERVER_ERROR, "版面区域格式错误");
            String type = row.get("type") instanceof String s ? s : null;
            ThrowUtil.throwIf(NullUtil.isNull(type), ErrorCode.MODEL_SERVER_ERROR, "版面区域缺少 type");
            List<?> box = row.get("bbox") instanceof List<?> list ? list : null;
            LayoutRegion region = new LayoutRegion();
            region.setType(type);
            region.setBbox(ModelResponseSupport.bbox(box, pixelsPerPt, "版面区域缺少合法 bbox"));
            if (row.get("confidence") instanceof Number confidence) {
                region.setConfidence(confidence.doubleValue());
            }
            regions.add(region);
            order.add(index);
        }
        LayoutResult result = new LayoutResult();
        result.setRegions(regions);
        result.setOrder(order);
        String model = ModelResponseSupport.modelKey(response);
        if (!NullUtil.isNull(model)) {
            result.setModel(model);
        }
        return result;
    }
}
