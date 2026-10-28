package com.workbuddy.worker.structure.impl;

import com.workbuddy.worker.structure.StructureProperties;
import com.workbuddy.worker.structure.judge.StructureJudgeProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 结构判定能力注册：候选 = Spring Bean 集合 ∩ 开关
 * `knowledge.structure.model-fallback.enabled`（默认 false）；当前无实现类，恒为 null，全走固定规则降级。
 */
@Component
public class StructureJudgeRegistry {

    private final List<StructureJudgeProvider> providers;
    private final StructureProperties properties;

    /**
     * 构造注册表：按配置选出生效的结构判定实现。
     *
     * @param providers  判定实现
     * @param properties 结构化阈值配置
     */
    public StructureJudgeRegistry(List<StructureJudgeProvider> providers, StructureProperties properties) {
        this.providers = providers;
        this.properties = properties;
    }

    /**
     * 启用的模型判定实现；没有时返回 null（走固定规则降级）。
     *
     * <p>多个实现时按注册顺序取首个（接入实现后如需优先级再补排序口径）。
     *
     * @return 生效的判定实现；未启用或无实现时为 null
     */
    public StructureJudgeProvider active() {
        return properties.isModelFallbackEnabled() && !providers.isEmpty() ? providers.getFirst() : null;
    }
}
