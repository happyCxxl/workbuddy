package com.workbuddy.worker.structure.craft;

import com.workbuddy.common.domain.structure.ConflictRecord;
import com.workbuddy.common.domain.structure.UnifiedElement;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 去重合并产出：合并后元素 + 冲突记录 + 合并对数（仅供组装流程内部流转）。
 */
@Data
public class MergeOutcome {

    /** 合并后元素 */
    private List<UnifiedElement> elements = new ArrayList<>();

    /** 无法裁决的冲突（双路并存：PRIMARY/BACKUP） */
    private List<ConflictRecord> conflicts = new ArrayList<>();

    /** 合并对数 */
    private int mergePairs;
}
