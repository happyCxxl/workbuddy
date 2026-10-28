package com.workbuddy.worker.structure.impl.title;

import com.workbuddy.worker.structure.title.TitleDecision;
import com.workbuddy.worker.structure.title.TitleRule;
import com.workbuddy.worker.structure.title.TitleRuleContext;

import java.util.regex.Pattern;

/**
 * 中文序号标题规则骨架：短文本且序号前缀命中才成立；有字号佐证定三级，否则计候选。
 * 优先级、正则与佐证样本由子类提供。
 */
abstract class AbstractCnNumberTitleRule implements TitleRule {

    private final int order;
    private final Pattern pattern;
    private final String sample;

    /**
     * 构造中文章节编号规则。
     *
     * @param order   链上顺序
     * @param pattern 编号模式
     * @param sample  示例串（证据用）
     */
    protected AbstractCnNumberTitleRule(int order, Pattern pattern, String sample) {
        this.order = order;
        this.pattern = pattern;
        this.sample = sample;
    }

    /**
     * 规则在链上的顺序。
     *
     * @return 顺序号
     */
    @Override
    public final int order() {
        return order;
    }

    /**
     * 按章节编号模式匹配并取层级。
     *
     * @param context 标题判定上下文
     * @return 命中结果；不适用时为 null
     */
    @Override
    public final TitleDecision tryMatch(TitleRuleContext context) {
        if (!context.shortText() || !pattern.matcher(context.text()).matches()) {
            return null;
        }
        return context.fontBacked()
                ? TitleDecision.title(3,
                        TitleDecision.evidence("number-pattern", context.fontSize(), context.bold(), sample))
                : TitleDecision.candidate();
    }
}
