package com.workbuddy.worker.embedding.impl;

import com.workbuddy.common.enums.embed.EmbedMetric;
import com.workbuddy.worker.embedding.check.ConsistencyResult;
import com.workbuddy.worker.embedding.strategy.EmbedStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 四关校验：数量、维度、空值坏值、度量与归一化。
 *
 * <p>重点是「失败类别」：数量与坏值可重试，维度与度量冲突不可重试。
 */
class EmbedConsistencyCheckerTest {

    private EmbedConsistencyChecker checker;

    private EmbedStrategy strategy;

    /**
     * 每个用例用一份独立策略，维度 3、度量 COSINE、归一化。
     */
    @BeforeEach
    void setUp() {
        checker = new EmbedConsistencyChecker();
        strategy = new EmbedStrategy();
        strategy.setDimension(3);
        strategy.setMetric(EmbedMetric.COSINE.key());
        strategy.setNormalized(true);
    }

    /**
     * 数量与输入一致且各关通过时返回通过。
     *
     * <p>守护 {@code check} 的第 ②③ 关通过分支：维度与取值都合法时返回通过。
     */
    @Test
    void passesWhenAllGatesSatisfied() {
        ConsistencyResult result = checker.check(
                List.of("a", "b"),
                List.of(vector(0.1f, 0.2f, 0.3f), vector(0.4f, 0.5f, 0.6f)),
                strategy);

        assertTrue(result.passed());
        assertTrue(result.problems().isEmpty());
    }

    /**
     * 返回向量为 null 时按数量不一致失败，且可重试。
     *
     * <p>守护 {@code check} 的第 ① 关：返回向量为 null，按数量不一致失败且可重试。
     */
    @Test
    void failsWhenVectorsNull() {
        ConsistencyResult result = checker.check(List.of("a"), null, strategy);

        assertFalse(result.passed());
        assertTrue(result.retryable());
        assertEquals(1, result.problems().size());
    }

    /**
     * 返回向量数少于输入文本数时按数量不一致失败，且可重试。
     *
     * <p>守护 {@code check} 的第 ① 关：返回条数与输入不一致时失败且可重试。
     */
    @Test
    void failsWhenCountMismatch() {
        ConsistencyResult result = checker.check(
                List.of("a", "b"),
                List.of(vector(0.1f, 0.2f, 0.3f)),
                strategy);

        assertFalse(result.passed());
        assertTrue(result.retryable());
        assertTrue(result.problems().getFirst().contains("不一致"));
    }

    /**
     * 空向量计入坏值，可重试；多条坏值一并汇报。
     *
     * <p>守护 {@code check} 的第 ③ 关：空向量计入坏值，可重试。
     */
    @Test
    void failsWhenVectorEmpty() {
        ConsistencyResult result = checker.check(
                List.of("a", "b"),
                Arrays.asList(Collections.emptyList(), vector(0.1f, 0.2f, 0.3f)),
                strategy);

        assertFalse(result.passed());
        assertTrue(result.retryable());
        assertEquals(1, result.problems().size());
        assertTrue(result.problems().getFirst().contains("为空"));
    }

    /**
     * 维度与目录声明不符时不可重试（重试也只会得到同样维度）。
     *
     * <p>守护 {@code check} 的第 ② 关：维度与目录声明不符，标记不可重试。
     */
    @Test
    void failsWithoutRetryWhenDimensionMismatch() {
        ConsistencyResult result = checker.check(
                List.of("a"),
                List.of(vector(0.1f, 0.2f)),
                strategy);

        assertFalse(result.passed());
        assertFalse(result.retryable());
        assertTrue(result.problems().getFirst().contains("维度"));
    }

    /**
     * NaN 与 Infinity 计入坏值，可重试。
     *
     * <p>守护 {@code check} 的第 ③ 关：NaN/Infinity 计入坏值，可重试。
     */
    @Test
    void failsWhenVectorContainsNonFinite() {
        ConsistencyResult result = checker.check(
                List.of("a"),
                List.of(vector(0.1f, Float.NaN, 0.3f)),
                strategy);

        assertFalse(result.passed());
        assertTrue(result.retryable());
        assertTrue(result.problems().getFirst().contains("NaN"));
    }

    /**
     * 维度未声明时跳过维度校验（不因任意长度误判）。
     *
     * <p>守护 {@code check} 的第 ② 关：维度未声明时跳过比对。
     */
    @Test
    void skipsDimensionCheckWhenNotDeclared() {
        strategy.setDimension(null);

        ConsistencyResult result = checker.check(
                List.of("a"),
                List.of(vector(0.1f, 0.2f, 0.3f, 0.4f)),
                strategy);

        assertTrue(result.passed());
    }

    /**
     * 维度声明为 0 时同样跳过维度校验（0 视同未声明）。
     *
     * <p>守护 {@code check} 的第 ② 关边界：把「大于 0 才比对」改成「不小于 0 就比对」即失败。
     */
    @Test
    void skipsDimensionCheckWhenDimensionZero() {
        strategy.setDimension(0);

        ConsistencyResult result = checker.check(
                List.of("a"),
                List.of(vector(0.1f, 0.2f, 0.3f)),
                strategy);

        assertTrue(result.passed());
    }

    /**
     * COSINE 要求归一化：声明为否即冲突，且不可重试。
     *
     * <p>守护 {@code check} 的第 ④ 关：COSINE 要求归一化，声明冲突即拒绝。
     */
    @Test
    void failsWhenCosineNotNormalized() {
        strategy.setNormalized(false);

        ConsistencyResult result = checker.check(
                List.of("a"),
                List.of(vector(0.1f, 0.2f, 0.3f)),
                strategy);

        assertFalse(result.passed());
        assertFalse(result.retryable());
        assertTrue(result.problems().getFirst().contains("COSINE"));
    }

    /**
     * L2 要求非归一化：声明为是即冲突，且不可重试。
     *
     * <p>守护 {@code check} 的第 ④ 关：L2 要求非归一化，声明冲突即拒绝。
     */
    @Test
    void failsWhenL2Normalized() {
        strategy.setMetric(EmbedMetric.L2.key());
        strategy.setNormalized(true);

        ConsistencyResult result = checker.check(
                List.of("a"),
                List.of(vector(0.1f, 0.2f, 0.3f)),
                strategy);

        assertFalse(result.passed());
        assertFalse(result.retryable());
        assertTrue(result.problems().getFirst().contains("L2"));
    }

    /**
     * L2 且非归一化时通过（度量与声明一致）。
     *
     * <p>守护 {@code check} 的第 ④ 关：L2 与非归一化的合法组合。
     */
    @Test
    void passesWhenL2NotNormalized() {
        strategy.setMetric(EmbedMetric.L2.key());
        strategy.setNormalized(false);

        ConsistencyResult result = checker.check(
                List.of("a"),
                List.of(vector(0.1f, 0.2f, 0.3f)),
                strategy);

        assertTrue(result.passed());
    }

    /**
     * 度量未声明时跳过归一化校验（缺失即不判冲突）。
     *
     * <p>守护 {@code check} 的第 ④ 关：度量未声明时跳过。
     */
    @Test
    void skipsMetricCheckWhenMetricMissing() {
        strategy.setMetric(null);

        ConsistencyResult result = checker.check(
                List.of("a"),
                List.of(vector(0.1f, 0.2f, 0.3f)),
                strategy);

        assertTrue(result.passed());
    }

    /**
     * 归一化未声明时同样跳过（两条件必须同时具备才判冲突）。
     *
     * <p>守护 {@code check} 的第 ④ 关：归一化未声明时跳过。
     */
    @Test
    void skipsMetricCheckWhenNormalizedMissing() {
        strategy.setNormalized(null);

        ConsistencyResult result = checker.check(
                List.of("a"),
                List.of(vector(0.1f, 0.2f, 0.3f)),
                strategy);

        assertTrue(result.passed());
    }

    /**
     * 构造定长向量。
     *
     * @param values 各维取值
     * @return 向量
     */
    private List<Float> vector(Float... values) {
        return List.of(values);
    }
}
