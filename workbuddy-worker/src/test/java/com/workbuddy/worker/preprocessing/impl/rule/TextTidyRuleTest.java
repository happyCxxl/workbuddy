package com.workbuddy.worker.preprocessing.impl.rule;
import com.workbuddy.common.enums.preprocess.ViewElementStatus;

import com.workbuddy.common.domain.preprocess.TraceEntry;
import com.workbuddy.common.domain.preprocess.ViewCell;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.worker.preprocessing.rule.RuleContext;
import com.workbuddy.worker.testkit.StrategyFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import com.workbuddy.worker.testkit.PropertiesFixtures;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 段落/表格文本整理规则单测：连字符/软连字符/重复标点/项目符号；表格无变化记 KEEP。
 */
class TextTidyRuleTest {

    private final TextTidyRule rule = new TextTidyRule();

    private RuleContext context() {
        RuleContext context = new RuleContext();
        context.setStrategy(StrategyFixtures.preprocessDefaults());
        context.setProperties(PropertiesFixtures.preprocess());
        return context;
    }

    private ViewElement paragraph(String display) {
        ViewElement element = new ViewElement();
        element.setElementId("n-1");
        element.setType("PARAGRAPH");
        element.setStatus(ViewElementStatus.NORMAL.name());
        element.setRawText(display);
        element.setDisplayText(display);
        element.setNormalizedText(display);
        return element;
    }

    /**
     * 守护 {@code hyphenationAndSoftHyphenShouldJoin}：该场景行为改动即失败。
     */
    @Test
    void hyphenationAndSoftHyphenShouldJoin() {
        ViewElement element = paragraph("well- known tech\u00ADnology");

        var outcome = rule.apply(element, context());

        assertTrue(outcome.isMatched());
        assertEquals("well-known technology", element.getDisplayText());
    }

    /**
     * 守护 {@code repeatedPunctuationAndBulletShouldTidy}：该场景行为改动即失败。
     */
    @Test
    void repeatedPunctuationAndBulletShouldTidy() {
        ViewElement element = paragraph("好！！•要点");

        var outcome = rule.apply(element, context());

        assertTrue(outcome.isMatched());
        assertEquals("好！• 要点", element.getDisplayText());
    }

    /**
     * 守护 {@code unchangedTableCellsShouldKeepTrace}：该场景行为改动即失败。
     */
    @Test
    void unchangedTableCellsShouldKeepTrace() {
        ViewElement element = paragraph(null);
        element.setType("TABLE");
        ViewCell cell = new ViewCell();
        cell.setCellId("tc-1");
        cell.setText("评分项");
        cell.setNormalizedText("评分项");
        element.setCells(List.of(cell));

        var outcome = rule.apply(element, context());

        assertTrue(outcome.isMatched());
        assertTrue(outcome.getTraces().stream()
                .anyMatch(t -> TraceEntry.ACTION_KEEP.equals(t.getAction())));
    }

    /**
     * 守护 {@code brokenLinesInSameParagraphShouldJoin}：该场景行为改动即失败。
     */
    @Test
    void brokenLinesInSameParagraphShouldJoin() {
        ViewElement element = paragraph("投标保证金为人民币\n叁佰万元整，\n请于开标前提交。");

        var outcome = rule.apply(element, context());

        assertTrue(outcome.isMatched());
        assertEquals("投标保证金为人民币叁佰万元整，请于开标前提交。", element.getDisplayText());
    }

    /**
     * 守护 {@code blankLineShouldKeepParagraphBoundary}：该场景行为改动即失败。
     */
    @Test
    void blankLineShouldKeepParagraphBoundary() {
        ViewElement element = paragraph("第一段内容\n\n第二段内容");

        rule.apply(element, context());

        assertEquals("第一段内容\n第二段内容", element.getDisplayText());
    }

    /**
     * 守护 {@code listItemsShouldNotBeJoined}：该场景行为改动即失败。
     */
    @Test
    void listItemsShouldNotBeJoined() {
        ViewElement element = paragraph("评分说明如下：\n1. 价格分\n2. 技术分");

        rule.apply(element, context());

        assertEquals("评分说明如下：\n1. 价格分\n2. 技术分", element.getDisplayText());
    }

    /**
     * 守护 {@code unchangedParagraphShouldNotMatch}：该场景行为改动即失败。
     */
    @Test
    void unchangedParagraphShouldNotMatch() {
        assertFalse(rule.apply(paragraph("正文段落无任何需要整理的内容"), context()).isMatched());
    }
}
