package com.workbuddy.worker.preprocessing.impl.rule;

import com.workbuddy.common.domain.preprocess.ViewCell;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.common.enums.preprocess.CustomRuleAction;
import com.workbuddy.worker.preprocessing.rule.CustomRuleMatcher;
import com.workbuddy.worker.preprocessing.strategy.PreprocessCustomRule;
import com.workbuddy.worker.preprocessing.PreprocessProperties;
import com.workbuddy.worker.preprocessing.strategy.PreprocessStrategy;
import com.workbuddy.worker.preprocessing.rule.RuleContext;
import com.workbuddy.worker.testkit.StrategyFixtures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自定义规则执行器单测：REMOVE/REPLACE 组引用/EXTRACT/顺序执行/单元格/开关。
 *
 * @author cxxl
 */
class CustomCleanRuleTest {

    private final CustomCleanRule rule = new CustomCleanRule();

    private ViewElement element(String text) {
        ViewElement element = new ViewElement();
        element.setElementId("n-1");
        element.setType("PARAGRAPH");
        element.setRawText(text);
        element.setDisplayText(text);
        element.setNormalizedText(text);
        return element;
    }

    private PreprocessCustomRule custom(String pattern, String action, String replacement) {
        PreprocessCustomRule customRule = new PreprocessCustomRule();
        customRule.setPattern(pattern);
        customRule.setAction(action);
        customRule.setReplacement(replacement);
        return customRule;
    }

    private RuleContext context(List<PreprocessCustomRule> rules) {
        PreprocessStrategy strategy = StrategyFixtures.preprocessDefaults();
        strategy.getCustom().setRules(rules);
        List<CustomRuleMatcher> matchers = new ArrayList<>();
        for (PreprocessCustomRule customRule : rules) {
            matchers.add(new CustomRuleMatcher(Pattern.compile(customRule.getPattern()),
                    CustomRuleAction.of(customRule.getAction()), customRule.getReplacement(),
                    customRule.getPattern()));
        }
        RuleContext context = new RuleContext();
        context.setStrategy(strategy);
        context.setProperties(new PreprocessProperties());
        context.setCustomRuleMatchers(matchers);
        return context;
    }

    @Test
    void removeShouldDropCopyrightLine() {
        ViewElement element = element("正文内容\n版权所有 © 某招标公司");

        var outcome = rule.apply(element, context(List.of(custom("版权所有\\s*©.*", "REMOVE", null))));

        assertTrue(outcome.isMatched());
        assertEquals("正文内容", element.getDisplayText().trim());
    }

    @Test
    void replaceShouldUseGroupReference() {
        ViewElement element = element("联系电话：13812345678");

        var outcome = rule.apply(element, context(List.of(
                custom("(联系电话[:：]\\s*)(\\d[\\d-]{6,})", "REPLACE", "$1***"))));

        assertTrue(outcome.isMatched());
        assertEquals("联系电话：***", element.getDisplayText());
    }

    @Test
    void extractShouldKeepOnlyMatch() {
        ViewElement element = element("招标编号：ABC-123，其余说明文字");

        var outcome = rule.apply(element, context(List.of(
                custom("招标编号[:：]\\s*([A-Z0-9-]+)", "EXTRACT", null))));

        assertTrue(outcome.isMatched());
        assertEquals("招标编号：ABC-123", element.getDisplayText());
    }

    @Test
    void rulesShouldApplyInOrder() {
        ViewElement element = element("水印A 正文 水印B");
        // 先删水印A，再把“正文”替换为“内容”：顺序敏感
        var outcome = rule.apply(element, context(List.of(
                custom("水印A ", "REMOVE", null),
                custom("正文", "REPLACE", "内容"))));

        assertTrue(outcome.isMatched());
        assertEquals("内容 水印B", element.getDisplayText());
    }

    @Test
    void cellsShouldBeProcessed() {
        ViewElement element = element(null);
        element.setType("TABLE");
        ViewCell cell = new ViewCell();
        cell.setCellId("tc-1");
        cell.setNormalizedText("电话 13812345678");
        element.setCells(List.of(cell));

        var outcome = rule.apply(element, context(List.of(
                custom("(\\d[\\d-]{6,})", "REPLACE", "***"))));

        assertTrue(outcome.isMatched());
        assertEquals("电话 ***", cell.getNormalizedText());
    }

    @Test
    void offShouldNotEnable() {
        PreprocessStrategy strategy = StrategyFixtures.preprocessDefaults();
        strategy.getCustom().setEnabled(PreprocessStrategy.OFF);
        strategy.getCustom().setRules(List.of(custom("x+", "REMOVE", null)));

        assertFalse(rule.enabledIn(strategy));
    }

    @Test
    void unmatchedShouldReturnNone() {
        ViewElement element = element("无命中文本");

        assertFalse(rule.apply(element, context(List.of(custom("不存在.*", "REMOVE", null)))).isMatched());
    }
}
