package com.workbuddy.worker.preprocessing.impl.rule;

import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.preprocessing.impl.TextBase;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.common.domain.preprocess.TraceEntry;
import com.workbuddy.common.domain.preprocess.ViewCell;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.common.enums.preprocess.CustomRuleAction;
import com.workbuddy.worker.preprocessing.rule.CleanRule;
import com.workbuddy.worker.preprocessing.rule.CustomRuleMatcher;
import com.workbuddy.worker.preprocessing.strategy.PreprocessStrategy;
import com.workbuddy.worker.preprocessing.rule.RuleContext;
import com.workbuddy.worker.preprocessing.rule.RuleOutcome;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

/**
 * ⑧ 自定义规则（链尾）：用户自定义正则规则（REMOVE/REPLACE/EXTRACT），
 * 按序作用于元素 displayText/normalizedText 与单元格文本，不动结构（与 TextTidyRule 同口径）。
 * 正则由管线在 buildRuleContext 预编译一次（RuleContext.customRuleMatchers），执行期只匹配。
 */
@Component
public class CustomCleanRule implements CleanRule {

    @Override
    public String name() {
        return "custom-clean-v1";
    }

    @Override
    public String stepName() {
        return "自定义规则";
    }

    @Override
    public int order() {
        return 8;
    }

    @Override
    public boolean enabledIn(PreprocessStrategy strategy) {
        return !strategy.customRules().isEmpty();
    }

    @Override
    public RuleOutcome apply(ViewElement element, RuleContext context) {
        List<CustomRuleMatcher> matchers = context.getCustomRuleMatchers();
        if (matchers.isEmpty()) {
            return RuleOutcome.none();
        }
        RuleOutcome outcome = new RuleOutcome();
        int changed = 0;

        if (StrUtil.isNotBlank(element.getDisplayText())) {
            String before = element.getDisplayText();
            String after = applyMatchers(before, matchers, outcome.getTraces());
            if (!after.equals(before)) {
                element.setDisplayText(after);
                element.setNormalizedText(TextBase.normalizeBase(after));
                changed++;
            }
        }
        if (NullUtil.isNotNull(element.getCells())) {
            for (ViewCell cell : element.getCells()) {
                if (StrUtil.isBlank(cell.getNormalizedText())) {
                    continue;
                }
                String after = applyMatchers(cell.getNormalizedText(), matchers, cell.getTrace());
                if (!after.equals(cell.getNormalizedText())) {
                    cell.setNormalizedText(after);
                    changed++;
                }
            }
        }
        if (changed == 0 && outcome.getTraces().isEmpty()) {
            return RuleOutcome.none();
        }
        outcome.setMatched(true);
        outcome.setChangedCount(changed);
        return outcome;
    }

    /** 按序应用全部自定义规则；命中轨迹写入 traces（REMOVE/EXTRACT 走 EXCLUDE，REPLACE 走 REPLACE） */
    private String applyMatchers(String text, List<CustomRuleMatcher> matchers, List<TraceEntry> traces) {
        String work = text;
        for (CustomRuleMatcher matcher : matchers) {
            String applied = switch (matcher.action()) {
                case REMOVE -> matcher.pattern().matcher(work).replaceAll("");
                case REPLACE -> matcher.pattern().matcher(work).replaceAll(matcher.replacement());
                case EXTRACT -> extractAll(work, matcher.pattern());
            };
            if (!applied.equals(work)) {
                traces.add(TraceEntry.of(name(), null,
                        matcher.action() == CustomRuleAction.REPLACE
                                ? TraceEntry.ACTION_REPLACE : TraceEntry.ACTION_EXCLUDE,
                        StrUtil.maxLength(work, 80), StrUtil.maxLength(applied, 80),
                        "自定义规则命中: " + matcher.sourcePattern()));
                work = applied;
            }
        }
        return work;
    }

    /** EXTRACT：提取全部命中（无命中返回原文）；多个命中以空格连接 */
    private String extractAll(String text, java.util.regex.Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        List<String> hits = new ArrayList<>();
        while (matcher.find()) {
            hits.add(matcher.group());
        }
        return hits.isEmpty() ? text : String.join(" ", hits);
    }
}
