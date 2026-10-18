package com.workbuddy.worker.structure.craft;

import com.workbuddy.common.domain.structure.UnifiedElement;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 结构组装产出：元素（类型/层级更新）+ 推定统计（内部流转，不出 worker）；
 * 关系由 {@link StructureAssembler#buildRelations} 在接续之后按最终元素表生成。
 */
@Data
public class TreeOutcome {

    /** 组装后元素 */
    private List<UnifiedElement> elements = new ArrayList<>();

    /** 标题推定数 */
    private int titleCount;

    /** 标题推定分布（按级联层） */
    private Map<String, Integer> titleCountByCascade;

    /** 标题候选告警数 */
    private int titleCandidateCount;
}
