package com.workbuddy.worker.preprocessing.impl.rule;
import com.workbuddy.common.enums.preprocess.ViewElementStatus;

import com.workbuddy.common.domain.preprocess.ViewCell;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.worker.preprocessing.PreprocessProperties;
import com.workbuddy.worker.preprocessing.rule.RuleContext;
import com.workbuddy.worker.testkit.StrategyFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 编码规范化规则单测：displayText 口径（空白/乱码）+ normalizedText 基座（NFKC 全角→半角）+ 单元格。
 */
class EncodingCleanRuleTest {

    private final EncodingCleanRule rule = new EncodingCleanRule();

    private RuleContext context() {
        RuleContext context = new RuleContext();
        context.setStrategy(StrategyFixtures.preprocessDefaults());
        context.setProperties(new PreprocessProperties());
        return context;
    }

    private ViewElement element(String raw) {
        ViewElement element = new ViewElement();
        element.setElementId("n-1");
        element.setType("PARAGRAPH");
        element.setStatus(ViewElementStatus.NORMAL.name());
        element.setRawText(raw);
        return element;
    }

    @Test
    void whitespaceAndControlShouldTidyDisplay() {
        ViewElement element = element("  a   b \u3000 c \n d ");

        var outcome = rule.apply(element, context());

        assertTrue(outcome.isMatched());
        assertEquals("a b c d", element.getDisplayText());
        assertEquals("a b c d", element.getNormalizedText());
        assertEquals("  a   b \u3000 c \n d ", element.getRawText());
    }

    @Test
    void fullwidthShouldConvertToHalfwidthInNormalizedOnly() {
        ViewElement element = element("预算￥１，２３４，５６７.８９");

        rule.apply(element, context());

        assertEquals("预算￥１，２３４，５６７.８９", element.getDisplayText());
        // NFKC 兼容映射：全角数字/逗号→半角，￥(U+FFE5)→¥(U+00A5)
        assertEquals("预算¥1,234,567.89", element.getNormalizedText());
    }

    @Test
    void undefinedCodepointsShouldBeStripped() {
        ViewElement element = element("a\uFFFFb");

        rule.apply(element, context());

        assertEquals("ab", element.getDisplayText());
    }

    @Test
    void cellsShouldBeNormalized() {
        ViewElement element = element(null);
        element.setType("TABLE");
        ViewCell cell = new ViewCell();
        cell.setCellId("tc-1");
        cell.setText(" Ａ cell \n");
        element.setCells(List.of(cell));

        rule.apply(element, context());

        assertEquals("A cell", cell.getNormalizedText());
    }

    @Test
    void unchangedShouldNotMatch() {
        ViewElement element = element("plain text");

        assertFalse(rule.apply(element, context()).isMatched());
    }
}
