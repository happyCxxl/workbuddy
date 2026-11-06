package com.workbuddy.worker.parser.capability.impl;

import com.workbuddy.common.domain.parse.capability.OcrLine;
import com.workbuddy.common.domain.parse.capability.OcrRegion;
import com.workbuddy.common.domain.parse.capability.ProviderContext;
import com.workbuddy.common.domain.parse.capability.StructuredOcrResult;
import com.workbuddy.common.enums.modelgateway.ModelScenarioKey;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.infra.modelgateway.ModelGatewayClient;
import com.workbuddy.infra.modelgateway.OcrResponse;
import com.workbuddy.worker.parser.ParseProperties;
import com.workbuddy.worker.parser.capability.StructuredOcrProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 结构化 OCR 的模型网关实现：经 ProviderContext 的资产字节入口取页图 → 网关 ocrLines →
 * fields.text_lines 映射为识别行（bbox 像素→pt）。prompt 与 schema 常量内置，模型由网关 catalog 路由，
 * 本类不感知模型名。
 */
@Component
@RequiredArgsConstructor
public class ModelGatewayStructuredOcrProvider implements StructuredOcrProvider {

    /** 业务场景键：提示词与 schema 文案在灵犀按业务统一管理，Java 侧不写死（见 ModelScenarioKey） */
    private static final ModelScenarioKey SCENARIO = ModelScenarioKey.OCR_LINES;

    private final ModelGatewayClient modelGatewayClient;
    private final ParseProperties properties;

    /**
     * 识别页面区域：页图字节缺失返回 null（管线按页记降级事实）；模型响应形态错误按 MODEL_SERVER_ERROR 抛出。
     *
     * @param region 待识别区域
     * @param ctx    调用上下文（含资产字节解析入口）
     * @return 结构化识别结果
     */
    @Override
    public StructuredOcrResult recognize(OcrRegion region, ProviderContext ctx) {
        Function<String, byte[]> resolver = NullUtil.isNull(ctx) ? null : ctx.getAssetBytes();
        byte[] image = NullUtil.isNull(resolver) ? null : resolver.apply(region.getImageRef());
        if (NullUtil.isNull(image) || image.length == 0) {
            return null;
        }
        String requestId = "parse-ocr-" + ctx.getTaskId() + "-p" + region.getPage();
        OcrResponse response = modelGatewayClient.ocrLines(requestId, "parse.ocr", image, "image/png",
                SCENARIO.promptKey(), Map.of(), SCENARIO.schemaKey(), 1);
        return toResult(response);
    }

    /** 网关直译 → StructuredOcrResult：文本与置信度原样，bbox 像素→pt（÷ dpi/72），模型名取 meta.model_key。 */
    private StructuredOcrResult toResult(OcrResponse response) {
        Object rawLines = NullUtil.isNull(response.fields()) ? null : response.fields().get("text_lines");
        List<?> rows = rawLines instanceof List<?> list ? list : null;
        ThrowUtil.throwIf(NullUtil.isNull(rows), ErrorCode.MODEL_SERVER_ERROR, "OCR 响应缺少 text_lines");
        double pixelsPerPt = properties.getPageImageDpi() / 72.0;
        List<OcrLine> lines = new ArrayList<>(rows.size());
        for (Object rawRow : rows) {
            Map<?, ?> row = rawRow instanceof Map<?, ?> map ? map : null;
            ThrowUtil.throwIf(NullUtil.isNull(row), ErrorCode.MODEL_SERVER_ERROR, "OCR 响应行格式错误");
            String text = row.get("text") instanceof String s ? s : null;
            ThrowUtil.throwIf(NullUtil.isNull(text), ErrorCode.MODEL_SERVER_ERROR, "OCR 响应行缺少 text");
            List<?> box = row.get("bbox") instanceof List<?> list ? list : null;
            OcrLine line = new OcrLine();
            line.setText(text);
            line.setBbox(ModelResponseSupport.bbox(box, pixelsPerPt, "OCR 响应行缺少合法 bbox"));
            if (row.get("confidence") instanceof Number confidence) {
                line.setConfidence(confidence.doubleValue());
            } else if (box.size() > 4 && box.get(4) instanceof Number tail
                    && tail.doubleValue() >= 0 && tail.doubleValue() <= 1) {
                // 模型偶把置信度塞进 bbox 数组尾部（[x0,y0,x1,y1,score]）：取值域内的尾项作置信度
                line.setConfidence(tail.doubleValue());
            }
            lines.add(line);
        }
        StructuredOcrResult result = new StructuredOcrResult();
        result.setLines(lines);
        String model = ModelResponseSupport.modelKey(response);
        if (!NullUtil.isNull(model)) {
            result.setModel(model);
        }
        return result;
    }
}
