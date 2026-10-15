package com.workbuddy.worker.structure.impl.title;

import com.workbuddy.worker.structure.title.TitleDecision;
import com.workbuddy.worker.structure.title.TitleRuleContext;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 编号标题规则共用口径：短句约束 + 正则整体匹配；含 CJK 且有字号佐证才定级，无佐证只计候选告警。
 *
 * @author cxxl
 */
final class NumberTitleSupport {

    /** CJK 统一表意文字区间 */
    private static final int CJK_START = 0x4E00;

    /** CJK 统一表意文字区间上界 */
    private static final int CJK_END = 0x9FFF;

    private NumberTitleSupport() {
    }

    /**
     * 编号匹配：短句约束成立且正则整体匹配时返回匹配器。
     *
     * @param context 规则上下文
     * @param pattern 编号正则
     * @return 匹配器；短句约束不成立或未匹配返回 null
     */
    static Matcher matchNumber(TitleRuleContext context, Pattern pattern) {
        if (!context.shortText()) {
            return null;
        }
        Matcher matcher = pattern.matcher(context.text());
        return matcher.matches() ? matcher : null;
    }

    /**
     * 编号标题判定：文本含 CJK 且字号佐证成立时按给定级数定标题，无佐证返回候选。
     *
     * @param context 规则上下文
     * @param level   命中后的标题级数
     * @param cascade 判定层级（证据里的级联名）
     * @param pattern 实际命中的编号串
     * @return 标题或候选判定
     */
    static TitleDecision decide(TitleRuleContext context, int level, String cascade, String pattern) {
        boolean hasCjk = context.text().codePoints().anyMatch(cp -> cp >= CJK_START && cp <= CJK_END);
        if (hasCjk && context.fontBacked()) {
            return TitleDecision.title(level,
                    TitleDecision.evidence(cascade, context.fontSize(), context.bold(), pattern));
        }
        return TitleDecision.candidate();
    }
}
