package com.workbuddy.worker.parser.capability.impl;

import com.workbuddy.common.domain.model.GenerationResult;
import com.workbuddy.common.domain.model.VisionRequest;
import com.workbuddy.common.domain.model.catalog.ModelCapability;
import com.workbuddy.common.domain.parse.capability.LayoutResult;
import com.workbuddy.common.domain.parse.capability.PageImage;
import com.workbuddy.common.domain.parse.capability.ProviderContext;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.model.gateway.ModelGatewayPort;
import com.workbuddy.worker.parser.capability.CapabilityProperties;
import com.workbuddy.worker.parser.capability.CapabilityRequests;
import com.workbuddy.worker.parser.capability.LayoutProvider;
import com.workbuddy.worker.parser.capability.prompt.CapabilityPrompts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 版面分析实现（视觉模态）：把页面信息、提示词与输出约束交给平台网关，响应 JSON 映射成
 * {@link LayoutResult}（区域分类 + 阅读顺序 + 模型名与版本）。映射保留在解析侧：区域清单与顺序数组
 * 按契约字段回填，模型名与版本取平台回传的标识（厂商侧不给版本时留空）。
 *
 * <p>候选模型由平台按 {@link ModelCapability#LAYOUT} 筛；开关关闭、页面为空即不发起调用；
 * 网关侧的失败一律按「能力失败」返回 null，主流程按内置几何口径输出阅读顺序。
 *
 * @author cxxl
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VisionLayoutProvider implements LayoutProvider {

    private final CapabilityProperties properties;

    private final ModelGatewayPort gateway;

    @Override
    public LayoutResult analyze(PageImage page, ProviderContext ctx) {
        if (!properties.isLayoutEnabled() || NullUtil.isNull(page)) {
            return null;
        }
        try {
            String prompt = CapabilityPrompts.layoutPrompt(page.getPage(), page.getWidth(), page.getHeight(),
                    page.getDpi());
            VisionRequest request = CapabilityRequests.of(page.getImageRef(), page.getPage(), null, prompt,
                    CapabilityPrompts.layoutOutput());
            GenerationResult result = gateway.visionByCapability(ModelCapability.LAYOUT, request);
            return toLayoutResult(result);
        } catch (RuntimeException e) {
            log.warn("版面能力调用失败，页码={}", page.getPage(), e);
            return null;
        }
    }

    /**
     * 响应映射：content 文本按契约反序列化成 {@link LayoutResult}，模型名与版本取平台回传的标识。
     *
     * @param result 平台出参
     * @return 版面分析结果；文本为空或反序列化不出时返回 null
     */
    private LayoutResult toLayoutResult(GenerationResult result) {
        if (NullUtil.isNull(result) || NullUtil.isNull(result.getContent())) {
            return null;
        }
        LayoutResult layout = JsonUtil.toObject(result.getContent(), LayoutResult.class);
        if (NullUtil.isNull(layout)) {
            return null;
        }
        layout.setModel(result.getModel());
        layout.setVersion(result.getModelVersion());
        return layout;
    }
}
