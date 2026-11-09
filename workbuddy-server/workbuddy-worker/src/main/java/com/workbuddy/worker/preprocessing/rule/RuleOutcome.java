package com.workbuddy.worker.preprocessing.rule;

import com.workbuddy.common.domain.preprocess.NormalizedField;
import com.workbuddy.common.domain.preprocess.TraceEntry;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 规则执行结果：命中轨迹（可多条，如元素内多个金额）+ 结构化字段 + 变更数 + 置信度。
 * 由管线聚合进 ViewElement 与子步骤统计。
 */
@Data
public class RuleOutcome {

    /** 是否命中（无命中则无轨迹） */
    private boolean matched;

    /** 命中轨迹（摘要级） */
    private List<TraceEntry> traces = new ArrayList<>();

    /** 提取/改写的结构化字段 */
    private List<NormalizedField> fields = new ArrayList<>();

    /** 实际改写次数（REPLACE/EXCLUDE 计数，统计用） */
    private int changedCount;

    /**
     * 置信度：本次处置的把握程度，取值 [0, 1]，null 表示未评估。
     *
     * <p>口径：命中轨迹中确定性动作（REPLACE/EXTRACT/MARK/EXCLUDE/KEEP）占比；
     * 全确定性动作 = 1.0，全是「拿不准」人工复核（MANUAL_REVIEW）= 0.0。
     * 未命中恒为 1.0（无动作即无改写风险）。规则可用自有信号覆盖该值，
     * 未覆盖时由管线按 {@link #confidenceOf(List)} 兜底。为假成功质检铺路：
     * 低置信度的「命中」是抽检优先级最高的候选。
     */
    private Double confidence;

    /**
     * 未命中结果。
     *
     * @return 未命中且无轨迹的结果（置信度 1.0：无动作无风险）
     */
    public static RuleOutcome none() {
        RuleOutcome outcome = new RuleOutcome();
        outcome.setConfidence(1.0);
        return outcome;
    }

    /**
     * 单轨迹命中结果。
     *
     * @param trace        命中轨迹
     * @param changedCount 实际改写次数
     * @return 命中结果（置信度按轨迹动作：MANUAL_REVIEW = 0.0，其余 = 1.0）
     */
    public static RuleOutcome hit(TraceEntry trace, int changedCount) {
        RuleOutcome outcome = new RuleOutcome();
        outcome.setMatched(true);
        outcome.getTraces().add(trace);
        outcome.setChangedCount(changedCount);
        outcome.setConfidence(confidenceOf(outcome.getTraces()));
        return outcome;
    }

    /**
     * 按轨迹动作算置信度：确定性动作占比。
     *
     * @param traces 命中轨迹
     * @return 置信度；轨迹为空返回 null（未评估）
     */
    public static Double confidenceOf(List<TraceEntry> traces) {
        if (traces.isEmpty()) {
            return null;
        }
        long deterministic = traces.stream()
                .filter(trace -> !TraceEntry.ACTION_MANUAL_REVIEW.equals(trace.getAction()))
                .count();
        return (double) deterministic / traces.size();
    }
}
