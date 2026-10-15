package com.workbuddy.worker.embedding.check;

import com.workbuddy.worker.embedding.strategy.EmbedStrategy;

import java.util.List;

/**
 * 四关一致性校验（写入前强制）：数量与顺序 / 维度 / 空值坏值 / 度量与归一化。
 * 返回 {@link ConsistencyResult}：pass=false 时按 retryable 分流——可重试（数量/空值，批次整体重试）
 * 或不可重试（维度/度量冲突，模型与目录不符，重试无意义 → 拒绝）。
 *
 * @author cxxl
 */
public interface ConsistencyChecker {

    /**
     * 校验一批模型返回向量。
     *
     * @param inputTexts 本批输入文本（数量/顺序基准；顺序对齐由供应商契约保证）
     * @param vectors    模型返回向量（与输入一一对应）
     * @param strategy   策略快照（维度/度量/归一化以目录冗余锁定为准）
     * @return 校验结果
     */
    ConsistencyResult check(List<String> inputTexts, List<List<Float>> vectors, EmbedStrategy strategy);
}
