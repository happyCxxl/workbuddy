package com.workbuddy.worker.structure.title;

/**
 * 标题判定规则（责任链）：每个规则一条编号/样式口径，管线按 {@link #order()} 升序依次尝试，
 * 首个非空判定即止（命中=title 或 candidate；candidate 亦为终态，不再试后续规则与模型兜底）。
 * 新增招投标编号模式 = 新增规则类（order 间隔 10 预留插入位），管线零改动。
 *
 * @author cxxl
 */
public interface TitleRule {

    /** 尝试顺序（升序；间隔 10 预留插入位） */
    int order();

    /**
     * 尝试判定标题。
     *
     * @param context 判定上下文（元素 + 文档字号基准 + 阈值配置）
     * @return 判定结果；不匹配返回 null（链继续下一条）
     */
    TitleDecision tryMatch(TitleRuleContext context);
}
