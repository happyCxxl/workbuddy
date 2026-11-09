package com.workbuddy.worker.preprocessing.rule;

import com.workbuddy.common.domain.preprocess.TraceEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 规则结果置信度：未命中恒 1.0、单轨迹按动作取值、多轨迹按确定性动作占比。
 */
class RuleOutcomeTest {

    /**
     * 未命中：置信度 1.0（无动作无改写风险）。
     */
    @Test
    void noneShouldBeFullyConfident() {
        RuleOutcome outcome = RuleOutcome.none();

        assertEquals(1.0, outcome.getConfidence());
    }

    /**
     * 单轨迹命中：确定性动作 1.0。
     */
    @Test
    void deterministicHitShouldBeFullyConfident() {
        RuleOutcome outcome = RuleOutcome.hit(
                TraceEntry.of("repeat-dispose-v1", null, TraceEntry.ACTION_EXCLUDE, "a", null, "跨页重复"), 1);

        assertEquals(1.0, outcome.getConfidence());
    }

    /**
     * 单轨迹命中：人工复核动作 0.0。
     */
    @Test
    void manualReviewHitShouldBeZeroConfidence() {
        RuleOutcome outcome = RuleOutcome.hit(
                TraceEntry.of("chinese-date-v1", "DATE", TraceEntry.ACTION_MANUAL_REVIEW, "甲子年", null,
                        "干支纪年（拿不准）"), 0);

        assertEquals(0.0, outcome.getConfidence());
    }

    /**
     * 多轨迹：确定性动作占比（3 条确定 + 1 条复核 = 0.75）。
     */
    @Test
    void mixedTracesShouldUseDeterministicRatio() {
        List<TraceEntry> traces = List.of(
                TraceEntry.of("r", null, TraceEntry.ACTION_REPLACE, "a", "b", "e"),
                TraceEntry.of("r", null, TraceEntry.ACTION_EXTRACT, "a", "b", "e"),
                TraceEntry.of("r", null, TraceEntry.ACTION_MARK, "a", null, "e"),
                TraceEntry.of("r", null, TraceEntry.ACTION_MANUAL_REVIEW, "a", null, "e"));

        assertEquals(0.75, RuleOutcome.confidenceOf(traces));
    }

    /**
     * 空轨迹：未评估（null），不硬凑置信度。
     */
    @Test
    void emptyTracesShouldBeUnassessed() {
        assertNull(RuleOutcome.confidenceOf(List.of()));
    }
}
