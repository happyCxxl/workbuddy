package com.workbuddy.worker.parser.capability.impl;

import com.workbuddy.common.domain.model.GenerationResult;
import com.workbuddy.common.domain.model.VisionRequest;
import com.workbuddy.common.domain.model.catalog.ModelCapability;
import com.workbuddy.common.domain.parse.capability.ProviderContext;
import com.workbuddy.common.domain.parse.capability.TableRegion;
import com.workbuddy.common.domain.parse.capability.TableResult;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.model.gateway.ModelGatewayPort;
import com.workbuddy.worker.parser.capability.CapabilityProperties;
import com.workbuddy.worker.parser.capability.CapabilityRequests;
import com.workbuddy.worker.parser.capability.TableStructureProvider;
import com.workbuddy.worker.parser.capability.prompt.CapabilityPrompts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 表格结构识别实现（视觉模态）：把失败表的区域、提示词与输出约束交给平台网关，响应 JSON 映射成
 * {@link TableResult}。映射保留在解析侧：行数、列数、单元格、置信度按契约字段回填，模型名与版本取
 * 平台回传的标识（厂商侧不给版本时留空）。
 *
 * <p>候选模型由平台按 {@link ModelCapability#TABLE_STRUCTURE} 筛；开关关闭、区域为空即不发起调用；
 * 网关侧的失败（厂商未配置、无候选、超时、非 2xx、输出不合 schema）一律按「能力失败」返回 null，
 * 主流程走内置降级并留痕。
 *
 * @author cxxl
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VisionTableStructureProvider implements TableStructureProvider {

    private final CapabilityProperties properties;

    private final ModelGatewayPort gateway;

    @Override
    public TableResult recognize(TableRegion region, ProviderContext ctx) {
        if (!properties.isTableEnabled() || NullUtil.isNull(region)) {
            return null;
        }
        try {
            String prompt = CapabilityPrompts.tablePrompt(region.getPage(), null, null, region.getOcrText());
            VisionRequest request = CapabilityRequests.of(region.getImageRef(), region.getPage(), region.getBbox(),
                    prompt, CapabilityPrompts.tableOutput());
            GenerationResult result = gateway.visionByCapability(ModelCapability.TABLE_STRUCTURE, request);
            return toTableResult(result);
        } catch (RuntimeException e) {
            log.warn("表格结构能力调用失败，页码={}", region.getPage(), e);
            return null;
        }
    }

    /**
     * 响应映射：content 文本按契约反序列化成 {@link TableResult}，模型名与版本取平台回传的标识。
     *
     * @param result 平台出参
     * @return 表格结构结果；文本为空或反序列化不出时返回 null
     */
    private TableResult toTableResult(GenerationResult result) {
        if (NullUtil.isNull(result) || NullUtil.isNull(result.getContent())) {
            return null;
        }
        TableResult table = JsonUtil.toObject(result.getContent(), TableResult.class);
        if (NullUtil.isNull(table)) {
            return null;
        }
        table.setModel(result.getModel());
        table.setVersion(result.getModelVersion());
        return table;
    }
}
