package com.workbuddy.worker.preprocessing.impl.rule;
import com.workbuddy.common.enums.preprocess.PreprocessFieldType;
import com.workbuddy.common.enums.preprocess.ViewElementStatus;

import com.workbuddy.common.domain.preprocess.TraceEntry;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.worker.preprocessing.PreprocessProperties;
import com.workbuddy.common.enums.preprocess.PreprocessRule;
import com.workbuddy.worker.preprocessing.strategy.PreprocessStrategy;
import com.workbuddy.worker.preprocessing.rule.RuleContext;
import com.workbuddy.worker.preprocessing.rule.RuleOutcome;
import com.workbuddy.worker.testkit.StrategyFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 字段规范化规则单测：11.4 全表（金额/日期/单位/证书号/编号/限定词）+ 冲突优先级 + MANUAL_REVIEW。
 */
class FieldNormalizeRuleTest {

    private final FieldNormalizeRule rule = new FieldNormalizeRule();

    private RuleContext context;

    @BeforeEach
    void setUp() {
        context = new RuleContext();
        context.setStrategy(StrategyFixtures.preprocessDefaults());
        context.setProperties(new PreprocessProperties());
        context.setTocLineCountByPage(new HashMap<>());
        context.setTocRunElementIds(new HashSet<>());
        context.setRepeatedPageNumbers(new HashSet<>());
        context.setNoisePageNumbers(new HashSet<>());
    }

    private ViewElement element(String text) {
        ViewElement element = new ViewElement();
        element.setElementId("n-1");
        element.setType("PARAGRAPH");
        element.setStatus(ViewElementStatus.NORMAL.name());
        element.setRawText(text);
        element.setDisplayText(text);
        element.setNormalizedText(text);
        return element;
    }

    private List<Map<String, String>> fields(RuleOutcome outcome) {
        return outcome.getFields().stream().map(f -> Map.of(
                "field", f.getField(), "value", f.getValue(),
                "unit", f.getUnit() == null ? "" : f.getUnit(),
                "rule", f.getRule())).toList();
    }

    @Test
    void thousandSeparatorShouldStripCommas() {
        ViewElement element = element("预算￥1,234,567.89元");

        RuleOutcome outcome = rule.apply(element, context);

        assertEquals("预算￥1234567.89元", element.getNormalizedText());
        assertTrue(fields(outcome).contains(Map.of(
                "field", PreprocessFieldType.AMOUNT.name(), "value", "1234567.89",
                "unit", "元", "rule", "thousand-sep-v1")));
    }

    @Test
    void chineseUppercaseAmountShouldExtractFieldWithoutRewritingText() {
        // 11.7 口径：大写保留原文、只提取字段
        ViewElement element = element("投标保证金为人民币叁佰万元整（￥3,000,000.00）。");

        RuleOutcome outcome = rule.apply(element, context);

        assertTrue(element.getNormalizedText().contains("叁佰万元整"));
        assertFalse(element.getNormalizedText().contains(","));
        assertTrue(fields(outcome).stream().anyMatch(f -> "3000000.00".equals(f.get("value"))
                && "amount-cn-v1".equals(f.get("rule"))));
    }

    @Test
    void chineseUppercaseAmountWithYiWanShouldParse() {
        ViewElement element = element("中标金额壹亿贰仟叁佰肆拾伍万陆仟柒佰捌拾玖元整");

        RuleOutcome outcome = rule.apply(element, context);

        assertTrue(fields(outcome).stream().anyMatch(f -> "123456789.00".equals(f.get("value"))));
    }

    @Test
    void complexAmountShouldMarkManualReview() {
        ViewElement element = element("预算壹亿贰亿叁万元整（拿不准）");

        RuleOutcome outcome = rule.apply(element, context);

        assertTrue(outcome.getTraces().stream()
                .anyMatch(t -> TraceEntry.ACTION_MANUAL_REVIEW.equals(t.getAction())));
        assertTrue(outcome.getFields().isEmpty());
    }

    @Test
    void chineseDateShouldConvertToIso() {
        ViewElement element = element("开标时间：二〇二六年八月二十五日");

        RuleOutcome outcome = rule.apply(element, context);

        assertEquals("开标时间：2026-08-25", element.getNormalizedText());
        assertTrue(fields(outcome).contains(Map.of(
                "field", PreprocessFieldType.DATE.name(), "value", "2026-08-25",
                "unit", "", "rule", "chinese-date-v1")));
    }

    @Test
    void chineseDateWithTensDayShouldConvert() {
        ViewElement element = element("工期截至二〇二六年十二月三十一日");

        rule.apply(element, context);

        assertTrue(element.getNormalizedText().contains("2026-12-31"));
    }

    @Test
    void separatorDateShouldUnifyAndPad() {
        ViewElement element = element("投标截止：2026/8/25");

        rule.apply(element, context);

        assertTrue(element.getNormalizedText().contains("2026-08-25"));
    }

    @Test
    void isoDateShouldBeIdempotent() {
        ViewElement element = element("投标截止：2026-08-25");

        RuleOutcome outcome = rule.apply(element, context);

        assertEquals("投标截止：2026-08-25", element.getNormalizedText());
        assertTrue(outcome.getTraces().isEmpty());
    }

    @Test
    void unitShouldNormalizeAndExtractArea() {
        ViewElement element = element("建筑面积 10㎡，另有 20m2 的场地");

        RuleOutcome outcome = rule.apply(element, context);

        assertEquals("建筑面积 10平方米，另有 20平方米 的场地", element.getNormalizedText());
        assertTrue(fields(outcome).contains(Map.of(
                "field", PreprocessFieldType.AREA.name(), "value", "10",
                "unit", "平方米", "rule", "unit-normalize-v1")));
    }

    @Test
    void certNoShouldExtractWithoutRewriting() {
        ViewElement element = element("证书编号：NO.123456，注册号: AB-7890");

        RuleOutcome outcome = rule.apply(element, context);

        assertEquals("证书编号：NO.123456，注册号: AB-7890", element.getNormalizedText());
        assertTrue(fields(outcome).stream().anyMatch(f -> "123456".equals(f.get("value"))
                && PreprocessFieldType.CERT_NO.name().equals(f.get("field"))));
        assertTrue(fields(outcome).stream().anyMatch(f -> "AB-7890".equals(f.get("value"))));
    }

    @Test
    void numberHierarchyAndQualifiersShouldStayUntouched() {
        ViewElement element = element("1.11 工期：质保期不低于 36 个月，不得少于 2 次培训");

        RuleOutcome outcome = rule.apply(element, context);

        assertEquals("1.11 工期：质保期不低于 36 个月，不得少于 2 次培训", element.getNormalizedText());
        assertTrue(outcome.getFields().isEmpty());
        assertTrue(outcome.getTraces().isEmpty());
    }

    @Test
    void ganzhiYearShouldMarkManualReview() {
        ViewElement element = element("庚子年三月五日签订");

        RuleOutcome outcome = rule.apply(element, context);

        assertTrue(outcome.getTraces().stream()
                .anyMatch(t -> TraceEntry.ACTION_MANUAL_REVIEW.equals(t.getAction())
                        && t.getRule().equals("chinese-date-v1")));
    }

    @Test
    void impossibleSeparatorDateShouldMarkManualReviewAndKeepText() {
        ViewElement element = element("投标截止：2026/2/30");

        RuleOutcome outcome = rule.apply(element, context);

        assertEquals("投标截止：2026/2/30", element.getNormalizedText());
        assertTrue(outcome.getFields().isEmpty());
        assertTrue(outcome.getTraces().stream()
                .anyMatch(t -> TraceEntry.ACTION_MANUAL_REVIEW.equals(t.getAction())
                        && t.getRule().equals("date-sep-v1")));
    }

    @Test
    void outOfRangeSeparatorDateShouldMarkManualReviewAndKeepText() {
        ViewElement element = element("开标时间 2026/13/45");

        RuleOutcome outcome = rule.apply(element, context);

        assertEquals("开标时间 2026/13/45", element.getNormalizedText());
        assertTrue(outcome.getTraces().stream()
                .anyMatch(t -> TraceEntry.ACTION_MANUAL_REVIEW.equals(t.getAction())));
    }

    @Test
    void leapDaySeparatorDateShouldConvert() {
        ViewElement element = element("投标截止：2028/2/29");

        rule.apply(element, context);

        assertTrue(element.getNormalizedText().contains("2028-02-29"));
    }

    @Test
    void impossibleChineseDateShouldMarkManualReviewAndKeepText() {
        ViewElement element = element("开标时间：二〇二六年二月三十日");

        RuleOutcome outcome = rule.apply(element, context);

        assertEquals("开标时间：二〇二六年二月三十日", element.getNormalizedText());
        assertTrue(outcome.getFields().isEmpty());
        assertTrue(outcome.getTraces().stream()
                .anyMatch(t -> TraceEntry.ACTION_MANUAL_REVIEW.equals(t.getAction())
                        && t.getRule().equals("chinese-date-v1")));
    }

    @Test
    void disabledShouldReportOff() {
        PreprocessStrategy strategy = StrategyFixtures.preprocessDefaults();
        strategy.rule(PreprocessRule.FIELD).setEnabled(PreprocessStrategy.OFF);

        assertFalse(rule.enabledIn(strategy));
        assertTrue(rule.enabledIn(StrategyFixtures.preprocessDefaults()));
    }

    @Test
    void cellsShouldBeNormalizedWithTraceOnCell() {
        ViewElement element = element(null);
        element.setType("TABLE");
        com.workbuddy.common.domain.preprocess.ViewCell cell =
                new com.workbuddy.common.domain.preprocess.ViewCell();
        cell.setCellId("tc-1");
        cell.setText("30,000");
        cell.setNormalizedText("30,000");
        element.setCells(List.of(cell));

        RuleOutcome outcome = rule.apply(element, context);

        assertEquals("30000", cell.getNormalizedText());
        assertNotNull(cell.getTrace());
        assertFalse(cell.getTrace().isEmpty());
        assertTrue(outcome.isMatched());
    }
}
