package com.workbuddy.worker.embedding.impl;

import com.workbuddy.common.enums.embed.EmbedMetric;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.embedding.check.ConsistencyChecker;
import com.workbuddy.worker.embedding.check.ConsistencyResult;
import com.workbuddy.worker.embedding.strategy.EmbedStrategy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 四关校验实现（写入前强制）：
 * ① 数量与顺序——返回向量数=输入文本数（顺序对齐由供应商契约保证）；
 * ② 维度——每条约=模型目录声明（策略快照冗余锁定）；
 * ③ 空值/坏值——空向量、NaN/Infinity 拦截；
 * ④ 度量与归一化——COSINE 必须归一化、L2 必须非归一化、IP 无约束（与目录冗余冲突即拒绝）。
 */
@Component
public class EmbedConsistencyChecker implements ConsistencyChecker {

    /**
     * 四关校验：数量、维度、度量与空值坏值。
     *
     * @param inputTexts 输入文本
     * @param vectors    向量
     * @param strategy   向量策略（维度、度量与归一化口径来源）
     * @return 校验结果
     */
    @Override
    public ConsistencyResult check(List<String> inputTexts, List<List<Float>> vectors, EmbedStrategy strategy) {
        List<String> problems = new ArrayList<>();
        boolean retryable = true;

        // ① 数量
        if (NullUtil.isNull(vectors) || vectors.size() != inputTexts.size()) {
            return ConsistencyResult.fail(true, List.of("返回向量数(" + size(vectors) + ")与输入文本数("
                    + inputTexts.size() + ")不一致"));
        }
        // ②③ 维度 + 空值/坏值
        int expectedDimension = NullUtil.isNull(strategy.getDimension()) ? -1 : strategy.getDimension();
        for (int i = 0; i < vectors.size(); i++) {
            List<Float> vector = vectors.get(i);
            if (NullUtil.isNull(vector) || vector.isEmpty()) {
                problems.add("第 " + (i + 1) + " 条向量为空");
                continue;
            }
            if (expectedDimension > 0 && vector.size() != expectedDimension) {
                problems.add("第 " + (i + 1) + " 条向量维度 " + vector.size() + " ≠ 目录声明 " + expectedDimension);
                // 维度不符是模型与目录不一致，重试无意义
                retryable = false;
                break;
            }
            for (int j = 0; j < vector.size(); j++) {
                Float value = vector.get(j);
                if (NullUtil.isNull(value) || !Float.isFinite(value)) {
                    problems.add("第 " + (i + 1) + " 条向量第 " + (j + 1) + " 维为 NaN/Infinity/空值");
                    break;
                }
            }
        }
        if (!problems.isEmpty()) {
            return ConsistencyResult.fail(retryable, problems);
        }
        // ④ 度量与归一化（与目录冗余锁定一致性）
        EmbedMetric metric = EmbedMetric.of(strategy.getMetric());
        Boolean normalized = strategy.getNormalized();
        if (NullUtil.isNotNull(metric) && NullUtil.isNotNull(normalized)) {
            if (EmbedMetric.COSINE.equals(metric) && !normalized) {
                return ConsistencyResult.fail(false, List.of("度量 COSINE 要求归一化，目录声明 normalized=false 冲突"));
            }
            if (EmbedMetric.L2.equals(metric) && normalized) {
                return ConsistencyResult.fail(false, List.of("度量 L2 要求非归一化，目录声明 normalized=true 冲突"));
            }
        }
        return ConsistencyResult.pass();
    }

    private int size(List<?> list) {
        return NullUtil.isNull(list) ? 0 : list.size();
    }
}
