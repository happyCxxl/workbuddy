package com.workbuddy.worker.parser.capability.impl;

import com.workbuddy.worker.parser.capability.CapabilityProperties;
import com.workbuddy.worker.parser.capability.CapabilityRegistry;
import com.workbuddy.worker.parser.capability.LayoutProvider;
import com.workbuddy.worker.parser.capability.StructuredOcrProvider;
import com.workbuddy.worker.parser.capability.TableStructureProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 能力注册表实现：候选 = Spring Bean 集合 ∩ 注册表开关；
 * 无启用实现返回 null，主流程走内置降级。
 *
 * @author cxxl
 */
@Component
@RequiredArgsConstructor
public class CapabilityRegistryImpl implements CapabilityRegistry {

    private final CapabilityProperties properties;
    private final List<StructuredOcrProvider> ocrProviders;
    private final List<LayoutProvider> layoutProviders;
    private final List<TableStructureProvider> tableProviders;

    @Override
    public StructuredOcrProvider ocr() {
        return properties.isOcrEnabled() && !ocrProviders.isEmpty() ? ocrProviders.getFirst() : null;
    }

    @Override
    public LayoutProvider layout() {
        return properties.isLayoutEnabled() && !layoutProviders.isEmpty() ? layoutProviders.getFirst() : null;
    }

    @Override
    public TableStructureProvider table() {
        return properties.isTableEnabled() && !tableProviders.isEmpty() ? tableProviders.getFirst() : null;
    }
}
