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

    protected AbstractCnNumberTitleRule(int order, Pattern pattern, String sample) {
        this.order = order;
        this.pattern = pattern;
        this.sample = sample;
    }

    @Override
    public final int order() {
        return order;
    }

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
