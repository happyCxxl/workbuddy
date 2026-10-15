package com.workbuddy.worker.structure.craft;

import com.workbuddy.common.domain.structure.UnifiedElement;
import com.workbuddy.worker.structure.AssembleContext;

import java.util.List;

/**
 * 去重与合并：多路结果找"同一个东西"（IoU + 文本相似双判定，缺一不可）。
 * 当前 native 单路直通；冲突无法裁决时双路并存保留（PRIMARY/BACKUP）。
 *
 * @author cxxl
 */
public interface DedupMerger {

    /**
     * 去重合并。
     *
     * @param elements 标准化后的统一元素
     * @param context  组装上下文
     * @return 合并产出（元素/冲突/合并对数）
     */
    MergeOutcome merge(List<UnifiedElement> elements, AssembleContext context);
}
