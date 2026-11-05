package com.workbuddy.worker.parser.capability.impl;

import com.workbuddy.worker.parser.capability.CapabilityRegistry;
import com.workbuddy.worker.parser.capability.LayoutProvider;
import com.workbuddy.worker.parser.capability.StructuredOcrProvider;
import com.workbuddy.worker.parser.capability.TableStructureProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 能力注册表实现：候选 = Spring Bean 集合（注册即启用，不做灰度开关）；无实现返回 null（主流程走内置降级）。
 * 已接入结构化 OCR（{@link ModelGatewayStructuredOcrProvider}）、版面分析（{@link ModelGatewayStructuredLayoutProvider}）
 * 与表格结构识别（{@link ModelGatewayStructuredTableProvider}）。
 */
@Component
@RequiredArgsConstructor
public class CapabilityRegistryImpl implements CapabilityRegistry {

    private final ObjectProvider<List<StructuredOcrProvider>> ocrProviders;
    private final ObjectProvider<List<LayoutProvider>> layoutProviders;
    private final ObjectProvider<List<TableStructureProvider>> tableProviders;

    /**
     * 启用的结构化 OCR 实现；无 → null。
     *
     * @return OCR 实现；无实现为 null
     */
    @Override
    public StructuredOcrProvider ocr() {
        List<StructuredOcrProvider> candidates = ocrProviders.getIfAvailable(List::of);
        return candidates.isEmpty() ? null : candidates.getFirst();
    }

    /**
     * 启用的版面分析实现；无 → null。
     *
     * @return 版面实现；无实现为 null
     */
    @Override
    public LayoutProvider layout() {
        List<LayoutProvider> candidates = layoutProviders.getIfAvailable(List::of);
        return candidates.isEmpty() ? null : candidates.getFirst();
    }

    /**
     * 启用的表格结构识别实现；无 → null。
     *
     * @return 表格实现；无实现为 null
     */
    @Override
    public TableStructureProvider table() {
        List<TableStructureProvider> candidates = tableProviders.getIfAvailable(List::of);
        return candidates.isEmpty() ? null : candidates.getFirst();
    }
}
