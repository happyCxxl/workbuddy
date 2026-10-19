package com.workbuddy.worker.preprocessing.impl.rule;
import com.workbuddy.common.enums.preprocess.ViewElementStatus;

import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.common.enums.preprocess.PreprocessRule;
import com.workbuddy.worker.preprocessing.PreprocessProperties;
import com.workbuddy.worker.preprocessing.strategy.PreprocessStrategy;
import com.workbuddy.worker.preprocessing.rule.RuleContext;
import com.workbuddy.worker.testkit.StrategyFixtures;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 噪声处置规则单测：KEEP（只统计不处置）/ MARK / EXCLUDE 三档；非噪声页不命中。
 */
class NoiseRuleTest {

    private final NoiseRule rule = new NoiseRule();

    private ViewElement element() {
        ViewElement element = new ViewElement();
        element.setElementId("n-1");
        element.setType("PARAGRAPH");
        element.setPage(9);
        element.setStatus(ViewElementStatus.NORMAL.name());
        element.setRawText("噪声页内容");
        element.setDisplayText("噪声页内容");
        element.setNormalizedText("噪声页内容");
        return element;
    }

    private RuleContext context(String action) {
        PreprocessStrategy strategy = StrategyFixtures.preprocessDefaults();
        strategy.rule(PreprocessRule.NOISE).setAction(action);
        RuleContext context = new RuleContext();
        context.setStrategy(strategy);
        context.setProperties(new PreprocessProperties());
        context.setTocLineCountByPage(new HashMap<>());
        context.setTocRunElementIds(new HashSet<>());
        context.setRepeatedPageNumbers(new HashSet<>());
        context.setNoisePageNumbers(Set.of(9));
        return context;
    }

    /**
     * 守护 {@code markShouldSetNoiseStatus}：该场景行为改动即失败。
     */
    @Test
    void markShouldSetNoiseStatus() {
        ViewElement element = element();
        var outcome = rule.apply(element, context("MARK"));

        assertTrue(outcome.isMatched());
        assertEquals(ViewElementStatus.NOISE.name(), element.getStatus());
        assertEquals("噪声页内容", element.getNormalizedText());
    }

    /**
     * 守护 {@code excludeShouldNullNormalizedText}：该场景行为改动即失败。
     */
    @Test
    void excludeShouldNullNormalizedText() {
        ViewElement element = element();
        rule.apply(element, context("EXCLUDE"));

        assertEquals(ViewElementStatus.EXCLUDED_NOISE.name(), element.getStatus());
        assertNull(element.getNormalizedText());
    }

    /**
     * 守护 {@code keepShouldResetStatusToNormalAndKeepText}：该场景行为改动即失败。
     */
    @Test
    void keepShouldResetStatusToNormalAndKeepText() {
        ViewElement element = element();
        var outcome = rule.apply(element, context("KEEP"));

        // 只统计不处置：状态回 NORMAL、检索文本原样（噪声计数来自组装环节页标记）
        assertTrue(outcome.isMatched());
        assertEquals(ViewElementStatus.NORMAL.name(), element.getStatus());
        assertEquals("噪声页内容", element.getNormalizedText());
    }

    /**
     * 守护 {@code nonNoisePageShouldNotMatch}：该场景行为改动即失败。
     */
    @Test
    void nonNoisePageShouldNotMatch() {
        ViewElement element = element();
        element.setPage(1);

        assertFalse(rule.apply(element, context("MARK")).isMatched());
        assertEquals(ViewElementStatus.NORMAL.name(), element.getStatus());
    }
}
