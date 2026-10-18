package com.workbuddy.worker.preprocessing.impl.rule;
import com.workbuddy.common.enums.preprocess.ViewElementStatus;

import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.common.enums.preprocess.PreprocessRule;
import com.workbuddy.worker.preprocessing.PreprocessProperties;
import com.workbuddy.worker.preprocessing.strategy.PreprocessStrategy;
import com.workbuddy.worker.preprocessing.rule.RuleContext;
import com.workbuddy.worker.testkit.StrategyFixtures;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 页眉页脚处置规则单测：KEEP/MARK/EXCLUDE 三档与类型路由。
 */
class HeaderFooterRuleTest {

    private final HeaderFooterRule rule = new HeaderFooterRule();

    private ViewElement element(String type) {
        ViewElement element = new ViewElement();
        element.setElementId("h-1");
        element.setType(type);
        element.setStatus(ViewElementStatus.NORMAL.name());
        element.setRawText("XX项目招标文件");
        element.setDisplayText("XX项目招标文件");
        element.setNormalizedText("XX项目招标文件");
        return element;
    }

    private RuleContext context(String pageHeaderFooter) {
        PreprocessStrategy strategy = StrategyFixtures.preprocessDefaults();
        strategy.rule(PreprocessRule.HEADER_FOOTER).setAction(pageHeaderFooter);
        RuleContext context = new RuleContext();
        context.setStrategy(strategy);
        context.setProperties(new PreprocessProperties());
        return context;
    }

    @Test
    void markShouldSetMarkedStatus() {
        ViewElement element = element("HEADER");
        var outcome = rule.apply(element, context("MARK"));

        assertTrue(outcome.isMatched());
        assertEquals(ViewElementStatus.MARKED_HEADER.name(), element.getStatus());
        assertEquals("XX项目招标文件", element.getNormalizedText());
        assertEquals(0, outcome.getChangedCount());
    }

    @Test
    void excludeShouldNullNormalizedText() {
        ViewElement element = element("FOOTER");
        var outcome = rule.apply(element, context("EXCLUDE"));

        assertTrue(outcome.isMatched());
        assertEquals(ViewElementStatus.EXCLUDED_FOOTER.name(), element.getStatus());
        assertNull(element.getNormalizedText());
        assertEquals(1, outcome.getChangedCount());
    }

    @Test
    void keepShouldStayNormal() {
        ViewElement element = element("HEADER");
        var outcome = rule.apply(element, context("KEEP"));

        assertTrue(outcome.isMatched());
        assertEquals(ViewElementStatus.NORMAL.name(), element.getStatus());
        assertEquals("XX项目招标文件", element.getNormalizedText());
    }

    @Test
    void nonHeaderFooterShouldNotMatch() {
        ViewElement element = element("PARAGRAPH");

        assertFalse(rule.apply(element, context("MARK")).isMatched());
        assertEquals(ViewElementStatus.NORMAL.name(), element.getStatus());
    }
}
