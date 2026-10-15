package com.workbuddy.worker.preprocessing.impl.rule;
import com.workbuddy.common.enums.structure.ElementMark;
import com.workbuddy.common.enums.preprocess.ViewElementStatus;

import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.common.enums.preprocess.PreprocessParam;
import com.workbuddy.common.enums.preprocess.PreprocessRule;
import com.workbuddy.worker.preprocessing.PreprocessProperties;
import com.workbuddy.worker.preprocessing.strategy.PreprocessStrategy;
import com.workbuddy.worker.preprocessing.rule.RuleContext;
import com.workbuddy.worker.testkit.StrategyFixtures;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 目录处置规则单测：整页聚合阈值（PDF）/连续窗口（Word）/非目录页不受影响/三档处置。
 *
 * @author cxxl
 */
class TocRuleTest {

    private final TocRule rule = new TocRule();

    private ViewElement tocElement(Integer page) {
        ViewElement element = new ViewElement();
        element.setElementId("n-" + page);
        element.setType("PARAGRAPH");
        element.setStatus(ViewElementStatus.NORMAL.name());
        element.setMarks(List.of(ElementMark.TOC_LINE.name()));
        element.setPage(page);
        element.setRawText("第一章 总则 ...... 1");
        element.setDisplayText("第一章 总则 ...... 1");
        element.setNormalizedText("第一章 总则 ...... 1");
        return element;
    }

    private RuleContext context(String action, Map<Integer, Integer> tocCount,
                                Set<String> tocRunIds) {
        PreprocessStrategy strategy = StrategyFixtures.preprocessDefaults();
        strategy.rule(PreprocessRule.TOC).setAction(action);
        return context(strategy, tocCount, tocRunIds);
    }

    private RuleContext context(PreprocessStrategy strategy, Map<Integer, Integer> tocCount,
                                Set<String> tocRunIds) {
        RuleContext context = new RuleContext();
        context.setStrategy(strategy);
        context.setProperties(new PreprocessProperties());
        context.setTocLineCountByPage(tocCount);
        context.setTocRunElementIds(tocRunIds);
        context.setRepeatedPageNumbers(new HashSet<>());
        context.setNoisePageNumbers(new HashSet<>());
        return context;
    }

    @Test
    void tocPageShouldMarkByThreshold() {
        Map<Integer, Integer> counts = new HashMap<>(Map.of(5, 3));
        ViewElement element = tocElement(5);

        var outcome = rule.apply(element, context("MARK", counts, new HashSet<>()));

        assertTrue(outcome.isMatched());
        assertEquals(ViewElementStatus.MARKED_TOC.name(), element.getStatus());
    }

    @Test
    void belowThresholdShouldNotDispose() {
        Map<Integer, Integer> counts = new HashMap<>(Map.of(5, 2));
        ViewElement element = tocElement(5);

        assertFalse(rule.apply(element, context("MARK", counts, new HashSet<>())).isMatched());
        assertEquals(ViewElementStatus.NORMAL.name(), element.getStatus());
    }

    @Test
    void wordWindowShouldDispose() {
        ViewElement element = tocElement(null);
        Set<String> runIds = new HashSet<>(Set.of("n-null"));

        var outcome = rule.apply(element, context("MARK", new HashMap<>(), runIds));

        assertTrue(outcome.isMatched());
        assertEquals(ViewElementStatus.MARKED_TOC.name(), element.getStatus());
    }

    @Test
    void excludeShouldNullNormalizedText() {
        Map<Integer, Integer> counts = new HashMap<>(Map.of(5, 3));
        ViewElement element = tocElement(5);

        rule.apply(element, context("EXCLUDE", counts, new HashSet<>()));

        assertEquals(ViewElementStatus.EXCLUDED_TOC.name(), element.getStatus());
        assertNull(element.getNormalizedText());
    }

    @Test
    void customThresholdShouldDriveDispose() {
        PreprocessStrategy strategy = StrategyFixtures.preprocessDefaults();
        strategy.rule(PreprocessRule.TOC).setAction("MARK");
        strategy.rule(PreprocessRule.TOC).getParams()
                .put(PreprocessParam.TOC_MIN_LINES_PER_PAGE.key(), "5");

        ViewElement belowThreshold = tocElement(5);
        assertFalse(rule.apply(belowThreshold,
                context(strategy, new HashMap<>(Map.of(5, 4)), new HashSet<>())).isMatched());
        assertEquals(ViewElementStatus.NORMAL.name(), belowThreshold.getStatus());

        ViewElement atThreshold = tocElement(5);
        assertTrue(rule.apply(atThreshold,
                context(strategy, new HashMap<>(Map.of(5, 5)), new HashSet<>())).isMatched());
        assertEquals(ViewElementStatus.MARKED_TOC.name(), atThreshold.getStatus());
    }

    @Test
    void keepShouldStayNormal() {
        ViewElement element = tocElement(5);

        var outcome = rule.apply(element, context("KEEP", new HashMap<>(Map.of(5, 3)), new HashSet<>()));

        assertTrue(outcome.isMatched());
        assertEquals(ViewElementStatus.NORMAL.name(), element.getStatus());
        assertEquals("第一章 总则 ...... 1", element.getNormalizedText());
    }

    @Test
    void nonTocElementShouldNotMatch() {
        ViewElement element = tocElement(5);
        element.setMarks(null);

        assertFalse(rule.apply(element, context("MARK", new HashMap<>(Map.of(5, 3)), new HashSet<>())).isMatched());
    }
}
