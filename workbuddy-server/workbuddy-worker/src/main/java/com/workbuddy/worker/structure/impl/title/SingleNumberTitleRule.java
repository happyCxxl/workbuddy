package com.workbuddy.worker.structure.impl.title;

import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.structure.title.TitleDecision;
import com.workbuddy.worker.structure.title.TitleRule;
import com.workbuddy.worker.structure.title.TitleRuleContext;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 单级编号标题规则：1 总则 / 1、总则 / 1. 总则 / 1) 总则（点数定级的多级形态归 NumberTitleRule）；
 * 分隔符后的内容须以非数字开头，避开 3.14 这类以数字开头的正文；
 * 防误判：含 CJK + 字号佐证才定级，无佐证只计候选告警。
 */
@Component
public class SingleNumberTitleRule implements TitleRule {

    /** 单级阿拉伯数字 + 一个分隔符（顿号/点/右括号/空白）+ 非数字开头的内容 */
    private static final Pattern NUMBER_PATTERN = Pattern.compile("^(\\d{1,2})(?:[、.．)）]|\\s)\\s*(\\D.*)$");

    /**
     * 规则在链上的顺序。
     *
     * @return 顺序号（小的先匹配）
     */
    @Override
    public int order() {
        return 25;
    }

    /**
     * 单数字编号标题：按单级数字编号匹配并取层级。
     *
     * @param context 标题判定上下文
     * @return 命中结果；不适用时为 null
     */
    @Override
    public TitleDecision tryMatch(TitleRuleContext context) {
        Matcher number = NumberTitleSupport.matchNumber(context, NUMBER_PATTERN);
        if (NullUtil.isNull(number)) {
            return null;
        }
        return NumberTitleSupport.decide(context, 1, "single-number-pattern", number.group(1));
    }
}
