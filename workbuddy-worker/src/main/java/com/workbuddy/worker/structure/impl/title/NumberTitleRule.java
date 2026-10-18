package com.workbuddy.worker.structure.impl.title;

import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.structure.title.TitleDecision;
import com.workbuddy.worker.structure.title.TitleRule;
import com.workbuddy.worker.structure.title.TitleRuleContext;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 多级数字编号标题规则：1.1 / 1.1.1 …（点数定级）；
 * 防误判：含 CJK + 字号佐证才定级，否则只计候选告警。
 */
@Component
public class NumberTitleRule implements TitleRule {

    /** 多级数字：1.1 / 1.1.1 …（点数定级） */
    private static final Pattern NUMBER_PATTERN = Pattern.compile("^(\\d+(?:\\.\\d+)+).*");

    @Override
    public int order() {
        return 30;
    }

    @Override
    public TitleDecision tryMatch(TitleRuleContext context) {
        Matcher number = NumberTitleSupport.matchNumber(context, NUMBER_PATTERN);
        if (NullUtil.isNull(number)) {
            return null;
        }
        int level = countDots(number.group(1)) + 1;
        return NumberTitleSupport.decide(context, level, "number-pattern", number.group(1));
    }

    private int countDots(String number) {
        int dots = 0;
        for (char c : number.toCharArray()) {
            if (c == '.') {
                dots++;
            }
        }
        return dots;
    }
}
