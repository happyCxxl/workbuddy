package com.workbuddy.worker.preprocessing.rule;

import com.workbuddy.worker.preprocessing.PreprocessProperties;
import com.workbuddy.worker.preprocessing.strategy.PreprocessStrategy;

import com.workbuddy.common.domain.structure.UnifiedDocument;
import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 规则执行上下文：文档级预计算集合（管线执行前一次算好，规则只读）。
 */
@Data
public class RuleContext {

    /** 统一文档（只读） */
    private UnifiedDocument document;

    /** 策略快照 */
    private PreprocessStrategy strategy;

    /** 阈值配置（全局默认值源；运行期参数以 strategy 为准） */
    private PreprocessProperties properties;

    /** 每页 TOC_LINE 元素数（PDF 目录页聚合判定） */
    private Map<Integer, Integer> tocLineCountByPage;

    /** Word 连续 TOC_LINE 窗口内元素 ID（≥ 策略 runMinLength 的连续段） */
    private Set<String> tocRunElementIds;

    /** 重复页页码（组装环节标记，除首份外） */
    private Set<Integer> repeatedPageNumbers;

    /** 噪声页页码（组装环节标记） */
    private Set<Integer> noisePageNumbers;

    /** 编译后的自定义规则（管线预编译一次；非法正则已剔除） */
    private List<CustomRuleMatcher> customRuleMatchers = List.of();
}
