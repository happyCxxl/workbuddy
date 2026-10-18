package com.workbuddy.worker.structure.impl.title;

import com.workbuddy.worker.structure.title.TitleDecision;
import com.workbuddy.worker.structure.title.TitleRule;
import com.workbuddy.worker.structure.title.TitleRuleContext;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 章级编号标题规则：第X章（一级）。
 */
@Component
public class ChapterTitleRule implements TitleRule {

    /** 一级：第X章 */
    private static final Pattern CHAPTER_PATTERN = Pattern.compile("^第[一二三四五六七八九十百零〇\\d]+章.*");

    @Override
    public int order() {
        return 20;
    }

    @Override
    public TitleDecision tryMatch(TitleRuleContext context) {
        if (!context.shortText() || !CHAPTER_PATTERN.matcher(context.text()).matches()) {
            return null;
        }
        return TitleDecision.title(1,
                TitleDecision.evidence("number-pattern", context.fontSize(), context.bold(), "第X章"));
    }
}
