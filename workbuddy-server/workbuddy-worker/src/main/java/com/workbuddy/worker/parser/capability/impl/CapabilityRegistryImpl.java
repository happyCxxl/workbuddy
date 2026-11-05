package com.workbuddy.worker.parser.capability.impl;

import com.workbuddy.worker.parser.ParseProperties;
import com.workbuddy.worker.parser.capability.CapabilityRegistry;
import com.workbuddy.worker.parser.capability.LayoutProvider;
import com.workbuddy.worker.parser.capability.StructuredOcrProvider;
import com.workbuddy.worker.parser.capability.TableStructureProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 能力注册表实现：候选 = Spring Bean 集合 ∩ 三项开关；无启用实现返回 null（主流程走内置降级）。
 * 已接入结构化 OCR（{@link ModelGatewayStructuredOcrProvider}）；版面与表格能力接入后同模式返回。
 */
@Component
@RequiredArgsConstructor
public class CapabilityRegistryImpl implements CapabilityRegistry {

    private final ObjectProvider<List<StructuredOcrProvider>> ocrProviders;
    private final ParseProperties properties;

    /**
     * 启用的结构化 OCR 实现；无 → null。
     *
     * @return OCR 实现；开关关闭或无实现为 null
     */
    @Override
    public StructuredOcrProvider ocr() {
        List<StructuredOcrProvider> candidates = ocrProviders.getIfAvailable(List::of);
        if (!properties.getCapabilities().isOcr() || candidates.isEmpty()) {
            return null;
        }
        return candidates.getFirst();
    }

    /**
     * 启用的版面分析实现；无 → null。
     *
     * @return 待接入，恒 null
     */
    @Override
    public LayoutProvider layout() {
        return null;
    }

    /**
     * 启用的表格结构识别实现；无 → null。
     *
     * @return 待接入，恒 null
     */
    @Override
    public TableStructureProvider table() {
        return null;
    }
}
