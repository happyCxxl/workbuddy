package com.workbuddy.worker.preprocessing.rule;

import com.workbuddy.worker.preprocessing.strategy.PreprocessStrategy;

import com.workbuddy.common.domain.preprocess.ViewElement;

/**
 * 清洗规则：固定规则链（order 1~8）的一步；自定义规则链尾执行，不实现本接口。
 * 顺序固定、逐条开关；规则只改派生视图元素（status/displayText/normalizedText/fields/trace），
 * 不碰原文 UnifiedDocument（原文零改动红线）。
 *
 * @author cxxl
 */
public interface CleanRule {

    /**
     * 规则 ID（进 trace，版本化，如 encoding-clean-v1）。
     */
    String name();

    /**
     * 子步骤名（进 kb_pipeline_step_log.step_name，如 编码规范化）。
     */
    String stepName();

    /**
     * 固定顺序位（1~8；不可调序，只开关）。
     */
    int order();

    /**
     * 是否启用（策略开关决定；encoding 等基础层规则同样按策略开关生效）。
     */
    boolean enabledIn(PreprocessStrategy strategy);

    /**
     * 执行处置：命中则产出动作与前后摘要（落元素 trace 与字段）。
     */
    RuleOutcome apply(ViewElement element, RuleContext context);
}
