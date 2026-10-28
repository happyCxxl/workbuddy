package com.workbuddy.common.enums.task;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 任务状态真值表测试：合法迁移图（5×5 全组合）、终态判定与非法迁移拦截。
 */
class PipelineTaskStatusTest {

    /** 期望的合法迁移图（与实现分开写，作为独立基准） */
    private static Map<PipelineTaskStatus, Set<PipelineTaskStatus>> expectedTargets() {
        Map<PipelineTaskStatus, Set<PipelineTaskStatus>> expected = new EnumMap<>(PipelineTaskStatus.class);
        expected.put(PipelineTaskStatus.QUEUED,
                EnumSet.of(PipelineTaskStatus.RUNNING, PipelineTaskStatus.FAILED));
        expected.put(PipelineTaskStatus.RUNNING,
                EnumSet.of(PipelineTaskStatus.SUCCESS, PipelineTaskStatus.PARTIAL_SUCCESS,
                        PipelineTaskStatus.FAILED));
        expected.put(PipelineTaskStatus.SUCCESS, EnumSet.noneOf(PipelineTaskStatus.class));
        expected.put(PipelineTaskStatus.PARTIAL_SUCCESS, EnumSet.noneOf(PipelineTaskStatus.class));
        expected.put(PipelineTaskStatus.FAILED, EnumSet.noneOf(PipelineTaskStatus.class));
        return expected;
    }

    /**
     * 守护 {@code statusSetShouldBeTheFiveKnownStates}：该场景行为改动即失败。
     */
    @Test
    void statusSetShouldBeTheFiveKnownStates() {
        assertEquals(5, PipelineTaskStatus.values().length);
    }

    /**
     * 守护 {@code allowedTargetsShouldEqualTheExactTruthTable}：该场景行为改动即失败。
     */
    @Test
    void allowedTargetsShouldEqualTheExactTruthTable() {
        Map<PipelineTaskStatus, Set<PipelineTaskStatus>> expected = expectedTargets();
        for (PipelineTaskStatus status : PipelineTaskStatus.values()) {
            assertEquals(expected.get(status), status.allowedTargets(), "allowedTargets of " + status);
        }
        // 合法迁移共 5 组：QUEUED 2 组 + RUNNING 3 组 + 三个终态各 0 组
        int legal = expected.values().stream().mapToInt(Set::size).sum();
        assertEquals(5, legal);
    }

    /**
     * 守护 {@code isTerminalShouldHoldForTheThreeFinalStatesOnly}：该场景行为改动即失败。
     */
    @Test
    void isTerminalShouldHoldForTheThreeFinalStatesOnly() {
        assertTrue(PipelineTaskStatus.SUCCESS.isTerminal());
        assertTrue(PipelineTaskStatus.PARTIAL_SUCCESS.isTerminal());
        assertTrue(PipelineTaskStatus.FAILED.isTerminal());
        assertFalse(PipelineTaskStatus.QUEUED.isTerminal());
        assertFalse(PipelineTaskStatus.RUNNING.isTerminal());
    }

    /**
     * 守护 {@code requireTransitionShouldAcceptAllLegalPairsAndRejectTheRest}：该场景行为改动即失败。
     */
    @Test
    void requireTransitionShouldAcceptAllLegalPairsAndRejectTheRest() {
        Map<PipelineTaskStatus, Set<PipelineTaskStatus>> expected = expectedTargets();
        int pairs = 0;
        int legal = 0;
        for (PipelineTaskStatus from : PipelineTaskStatus.values()) {
            for (PipelineTaskStatus to : PipelineTaskStatus.values()) {
                pairs++;
                if (expected.get(from).contains(to)) {
                    assertEquals(to, PipelineTaskStatus.requireTransition(from, to));
                    legal++;
                } else {
                    assertThrows(IllegalArgumentException.class,
                            () -> PipelineTaskStatus.requireTransition(from, to), from + " → " + to);
                }
            }
        }
        assertEquals(25, pairs);
        assertEquals(5, legal);
    }

    /**
     * 守护 {@code requireTransitionShouldRejectTypicalIllegalPairs}：该场景行为改动即失败。
     */
    @Test
    void requireTransitionShouldRejectTypicalIllegalPairs() {
        assertThrows(IllegalArgumentException.class,
                () -> PipelineTaskStatus.requireTransition(PipelineTaskStatus.SUCCESS, PipelineTaskStatus.FAILED));
        assertThrows(IllegalArgumentException.class,
                () -> PipelineTaskStatus.requireTransition(PipelineTaskStatus.FAILED, PipelineTaskStatus.SUCCESS));
        assertThrows(IllegalArgumentException.class,
                () -> PipelineTaskStatus.requireTransition(PipelineTaskStatus.RUNNING, PipelineTaskStatus.QUEUED));
        assertThrows(IllegalArgumentException.class,
                () -> PipelineTaskStatus.requireTransition(PipelineTaskStatus.QUEUED, PipelineTaskStatus.SUCCESS));
    }

    /**
     * 守护 {@code requireTransitionShouldRejectNullSource}：该场景行为改动即失败。
     */
    @Test
    void requireTransitionShouldRejectNullSource() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PipelineTaskStatus.requireTransition(null, PipelineTaskStatus.RUNNING));
        assertTrue(e.getMessage().contains("不能为空"), e.getMessage());
    }

    /**
     * 守护 {@code requireTransitionShouldRejectNullTarget}：该场景行为改动即失败。
     */
    @Test
    void requireTransitionShouldRejectNullTarget() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PipelineTaskStatus.requireTransition(PipelineTaskStatus.RUNNING, null));
        assertTrue(e.getMessage().contains("不能为空"), e.getMessage());
    }
}
