package com.workbuddy.worker.structure.craft;

import com.workbuddy.common.domain.structure.DocumentRelation;
import com.workbuddy.common.domain.structure.UnifiedElement;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 跨页接续产出：接续后元素 + 关系（CONTINUATION_OF）+ 统计（内部流转，不出 worker）。
 */
@Data
public class ContinuationOutcome {

    /** 接续后元素 */
    private List<UnifiedElement> elements = new ArrayList<>();

    /** 接续关系（CONTINUATION_OF） */
    private List<DocumentRelation> relations = new ArrayList<>();

    /** 接续表数 */
    private int continuationCount;

    /** 疑似接续表数（放宽规则命中） */
    private int suspectedCount;
}
