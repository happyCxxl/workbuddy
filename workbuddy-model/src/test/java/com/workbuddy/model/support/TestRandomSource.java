package com.workbuddy.model.support;

import com.workbuddy.common.domain.model.catalog.ModelCapability;
import com.workbuddy.model.route.RandomSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 取值可预测的随机源（测试用）：三种模式 —— 固定值（每次返回同一个值）、序列（按序循环取值）
 * 与固定种子的随机数（取值多变但每次运行一致）。取值跨越取样上界时按上界取模。
 *
 * @author cxxl
 */
public class TestRandomSource implements RandomSource {

    /** 固定取值 */
    private final int fixed;

    /** 序列取值 */
    private final List<Integer> sequence;

    /** 固定种子的随机数发生器 */
    private final Random random;

    /** 序列取值的位置 */
    private final AtomicInteger cursor = new AtomicInteger();

    private TestRandomSource(int fixed, List<Integer> sequence, Random random) {
        this.fixed = fixed;
        this.sequence = sequence;
        this.random = random;
    }

    /** 固定取值：每次返回同一个值 */
    public static TestRandomSource fixed(int value) {
        return new TestRandomSource(value, List.of(), null);
    }

    /**
     * 首次取值固定、其后按序列循环：用于「前几次放行、后面轮到另一个」的用例。
     *
     * @param first  第一次返回的值
     * @param others 其余取值（按序循环）
     * @return 随机源
     */
    public static TestRandomSource firstThen(int first, int... others) {
        List<Integer> sequence = new ArrayList<>();
        sequence.add(first);
        for (int value : others) {
            sequence.add(value);
        }
        return new TestRandomSource(0, List.copyOf(sequence), null);
    }

    /**
     * 序列取值：按序循环返回，用尽后从头开始。
     *
     * @param values 取值序列（至少一项）
     * @return 随机源
     */
    public static TestRandomSource sequence(int... values) {
        List<Integer> sequence = new ArrayList<>();
        for (int value : values) {
            sequence.add(value);
        }
        if (sequence.isEmpty()) {
            throw new IllegalArgumentException("序列取值至少一项");
        }
        return new TestRandomSource(0, List.copyOf(sequence), null);
    }

    /**
     * 固定种子的随机数：取值多变，同一颗种子每次运行得到同一串取值。
     *
     * @param random 随机数发生器
     * @return 随机源
     */
    public static TestRandomSource random(Random random) {
        return new TestRandomSource(0, List.of(), random);
    }

    @Override
    public int nextInt(int bound) {
        if (random != null) {
            return random.nextInt(bound);
        }
        if (sequence.isEmpty()) {
            return Math.floorMod(fixed, bound);
        }
        int picked = sequence.get(Math.floorMod(cursor.getAndIncrement(), sequence.size()));
        return Math.floorMod(picked, bound);
    }
}
