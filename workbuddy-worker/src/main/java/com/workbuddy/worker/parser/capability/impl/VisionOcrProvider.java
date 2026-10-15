package com.workbuddy.worker.parser.capability.impl;

import com.workbuddy.common.domain.model.GenerationResult;
import com.workbuddy.common.domain.model.VisionRequest;
import com.workbuddy.common.domain.model.catalog.ModelCapability;
import com.workbuddy.common.domain.parse.capability.OcrRegion;
import com.workbuddy.common.domain.parse.capability.ProviderContext;
import com.workbuddy.common.domain.parse.capability.StructuredOcrResult;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.model.gateway.ModelGatewayPort;
import com.workbuddy.worker.parser.capability.CapabilityProperties;
import com.workbuddy.worker.parser.capability.CapabilityRequests;
import com.workbuddy.worker.parser.capability.StructuredOcrProvider;
import com.workbuddy.worker.parser.capability.prompt.CapabilityPrompts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 结构化 OCR 实现（视觉模态）：把待识别区域、提示词与输出约束交给平台网关，响应 JSON 映射成
 * {@link StructuredOcrResult}（逐行文本 + 包围盒 + 置信度 + 模型名与版本）。映射保留在解析侧：
 * 行清单按契约字段回填，模型名与版本取平台回传的标识（厂商侧不给版本时留空）。
 *
 * <p>候选模型由平台按 {@link ModelCapability#OCR} 筛；开关关闭、区域为空即不发起调用；
 * 网关侧的失败一律按「能力失败」返回 null，主流程保持原样（图片只记引用与图注）。
 *
 * @author cxxl
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VisionOcrProvider implements StructuredOcrProvider {

    private final CapabilityProperties properties;

    private final ModelGatewayPort gateway;

    @Override
    public StructuredOcrResult recognize(OcrRegion region, ProviderContext ctx) {
        if (!properties.isOcrEnabled() || NullUtil.isNull(region)) {
            return null;
        }
        try {
            String prompt = CapabilityPrompts.ocrPrompt(region.getPage(), bboxText(region));
            VisionRequest request = CapabilityRequests.of(region.getImageRef(), region.getPage(), region.getBbox(),
                    prompt, CapabilityPrompts.ocrOutput());
            GenerationResult result = gateway.visionByCapability(ModelCapability.OCR, request);
            return toOcrResult(result);
        } catch (RuntimeException e) {
            log.warn("结构化 OCR 能力调用失败，页码={}", region.getPage(), e);
            return null;
        }
    }

    /**
     * 响应映射：content 文本按契约反序列化成 {@link StructuredOcrResult}，模型名与版本取平台回传的标识。
     *
     * @param result 平台出参
     * @return 识别结果；文本为空或反序列化不出时返回 null
     */
    private StructuredOcrResult toOcrResult(GenerationResult result) {
        if (NullUtil.isNull(result) || NullUtil.isNull(result.getContent())) {
            return null;
        }
        StructuredOcrResult ocr = JsonUtil.toObject(result.getContent(), StructuredOcrResult.class);
        if (NullUtil.isNull(ocr)) {
            return null;
        }
        ocr.setModel(result.getModel());
        ocr.setVersion(result.getModelVersion());
        return ocr;
    }

    /** 区域包围盒的提示词文本：包围盒缺失返回 null */
    private String bboxText(OcrRegion region) {
        if (NullUtil.isNull(region.getBbox())) {
            return null;
        }
        return "x=" + region.getBbox().getX() + ", y=" + region.getBbox().getY()
                + ", width=" + region.getBbox().getWidth() + ", height=" + region.getBbox().getHeight();
    }
}
