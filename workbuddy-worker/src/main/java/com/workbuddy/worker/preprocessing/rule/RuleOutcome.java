package com.workbuddy.worker.preprocessing.rule;

import com.workbuddy.common.domain.preprocess.NormalizedField;
import com.workbuddy.common.domain.preprocess.TraceEntry;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 规则执行结果：命中轨迹（可多条，如元素内多个金额）+ 结构化字段 + 变更数。
 * 由管线聚合进 ViewElement 与子步骤统计。
 *
 * @author cxxl
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
     * 未命中结果。
     */
    public static RuleOutcome none() {
        return new RuleOutcome();
    }

    /**
     * 单轨迹命中结果。
     */
    public static RuleOutcome hit(TraceEntry trace, int changedCount) {
        RuleOutcome outcome = new RuleOutcome();
        outcome.setMatched(true);
        outcome.getTraces().add(trace);
        outcome.setChangedCount(changedCount);
        return outcome;
    }
}
