package com.workbuddy.worker.structure.impl.title;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.worker.structure.title.TitleDecision;
import com.workbuddy.worker.structure.title.TitleRule;
import com.workbuddy.worker.structure.title.TitleRuleContext;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 原生样式标题规则（级联第一层）：DOCX Heading1~9 直接定级（样式名须完整匹配，级数收敛到 1~9）。
 *
 * @author cxxl
 */
@Component
public class StyleTitleRule implements TitleRule {

    /** DOCX 样式标题：Heading1~9（锚定匹配，Heading1Char 这类字符样式名不算） */
    private static final Pattern STYLE_PATTERN = Pattern.compile("^\\s*Heading\\s*(\\d+)\\s*$", Pattern.CASE_INSENSITIVE);

    /** 级数下限：Heading0 之类的越界值不作为标题 */
    private static final int MIN_LEVEL = 1;

    /** 级数上限：DOCX Heading1~9 的 9 级 */
    private static final int MAX_LEVEL = 9;

    @Override
    public int order() {
        return 10;
    }

    @Override
    public TitleDecision tryMatch(TitleRuleContext context) {
        String style = context.style();
        if (StrUtil.isBlank(style) || !context.shortText()) {
            return null;
        }
        Matcher matcher = STYLE_PATTERN.matcher(style);
        if (!matcher.matches()) {
            return null;
        }
        int level = Integer.parseInt(matcher.group(1));
        if (level < MIN_LEVEL) {
            return null;
        }
        return TitleDecision.title(Math.min(level, MAX_LEVEL),
                TitleDecision.evidence("style", context.fontSize(), context.bold(), style));
    }
}
