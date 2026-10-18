package com.workbuddy.worker.parser.capability.impl;

import com.workbuddy.worker.parser.capability.CapabilityRegistry;
import com.workbuddy.worker.parser.capability.LayoutProvider;
import com.workbuddy.worker.parser.capability.StructuredOcrProvider;
import com.workbuddy.worker.parser.capability.TableStructureProvider;
import org.springframework.stereotype.Component;

/**
 * 能力注册表占位实现：三项模型能力（结构化 OCR / 版面分析 / 表格结构）的实现类已随模型调用层一并移除，
 * 三个口一律返回 null，主流程恒走内置降级。
 *
 * <p>接入模型能力后改回「候选 = Spring Bean 集合 ∩ 三项开关」：本类按 {@link CapabilityRegistry}
 * 的三个口返回已注册的实现，开关关闭或无实现即返回 null。
 */
@Component
public class CapabilityRegistryImpl implements CapabilityRegistry {

    @Override
    public StructuredOcrProvider ocr() {
        return null;
    }

    @Override
    public LayoutProvider layout() {
        return null;
    }

    @Override
    public TableStructureProvider table() {
        return null;
    }
}
