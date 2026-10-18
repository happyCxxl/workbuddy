package com.workbuddy.worker.preprocessing.impl.rule;
import com.workbuddy.common.enums.structure.ElementMark;
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
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重复处置规则单测：段级/页级标记按三档处置（EXCLUDE 只留首份 / MARK 标记保留 / KEEP 不改动）。
 */
class RepeatRuleTest {

    private final RepeatRule rule = new RepeatRule();

    private ViewElement element() {
        ViewElement element = new ViewElement();
        element.setElementId("n-1");
        element.setType("PARAGRAPH");
        element.setStatus(ViewElementStatus.NORMAL.name());
        element.setRawText("重复段落内容");
        element.setDisplayText("重复段落内容");
        element.setNormalizedText("重复段落内容");
        return element;
    }

    private RuleContext context(Set<Integer> repeatedPages) {
        return context(StrategyFixtures.preprocessDefaults(), repeatedPages);
    }

    private RuleContext context(String action, Set<Integer> repeatedPages) {
        PreprocessStrategy strategy = StrategyFixtures.preprocessDefaults();
        strategy.rule(PreprocessRule.REPEAT).setAction(action);
        return context(strategy, repeatedPages);
    }

    private RuleContext context(PreprocessStrategy strategy, Set<Integer> repeatedPages) {
        RuleContext context = new RuleContext();
        context.setStrategy(strategy);
        context.setProperties(new PreprocessProperties());
        context.setTocLineCountByPage(new HashMap<>());
        context.setTocRunElementIds(new HashSet<>());
        context.setRepeatedPageNumbers(repeatedPages);
        context.setNoisePageNumbers(new HashSet<>());
        return context;
    }

    @Test
    void repeatedSegmentShouldExcludeByDefault() {
        ViewElement element = element();
        element.setMarks(List.of(ElementMark.REPEATED_SEGMENT.name()));

        var outcome = rule.apply(element, context(new HashSet<>()));

        assertTrue(outcome.isMatched());
        assertEquals(ViewElementStatus.REPEATED.name(), element.getStatus());
        assertNull(element.getNormalizedText());
    }

    @Test
    void repeatedPageShouldExcludeByDefault() {
        ViewElement element = element();
        element.setPage(7);

        var outcome = rule.apply(element, context(new HashSet<>(Set.of(7))));

        assertTrue(outcome.isMatched());
        assertEquals(ViewElementStatus.REPEATED.name(), element.getStatus());
        assertNull(element.getNormalizedText());
    }

    @Test
    void repeatedSegmentMarkShouldKeepTextWithMarkedStatus() {
        ViewElement element = element();
        element.setMarks(List.of(ElementMark.REPEATED_SEGMENT.name()));

        var outcome = rule.apply(element, context("MARK", new HashSet<>()));

        assertTrue(outcome.isMatched());
        assertEquals(ViewElementStatus.MARKED_REPEAT.name(), element.getStatus());
        assertEquals("重复段落内容", element.getNormalizedText());
    }

    @Test
    void repeatedSegmentKeepShouldResetStatusToNormal() {
        ViewElement element = element();
        element.setMarks(List.of(ElementMark.REPEATED_SEGMENT.name()));

        var outcome = rule.apply(element, context("KEEP", new HashSet<>()));

        assertTrue(outcome.isMatched());
        assertEquals(ViewElementStatus.NORMAL.name(), element.getStatus());
        assertEquals("重复段落内容", element.getNormalizedText());
    }

    @Test
    void repeatedPageMarkShouldKeepTextWithMarkedStatus() {
        ViewElement element = element();
        element.setPage(7);

        var outcome = rule.apply(element, context("MARK", new HashSet<>(Set.of(7))));

        assertTrue(outcome.isMatched());
        assertEquals(ViewElementStatus.MARKED_REPEAT.name(), element.getStatus());
        assertEquals("重复段落内容", element.getNormalizedText());
    }

    @Test
    void enabledInShouldFollowRulePresence() {
        assertTrue(rule.enabledIn(StrategyFixtures.preprocessDefaults()));

        PreprocessStrategy removed = StrategyFixtures.preprocessDefaults();
        removed.getRules().remove(PreprocessRule.REPEAT.key());

        assertFalse(rule.enabledIn(removed));
    }

    @Test
    void unmarkedShouldNotMatch() {
        assertFalse(rule.apply(element(), context(new HashSet<>())).isMatched());
    }
}
