package com.workbuddy.worker.structure.craft;

import com.workbuddy.common.domain.structure.UnifiedDocument;

/**
 * 重复/噪声识别（组装环节）：基于 UnifiedDocument 全文档比较打标记，
 * 只写 UnifiedPage.marks / UnifiedElement.marks，不改结构/阅读顺序/章节树。
 * 处置在预处理环节（只保留一份/标记/剔除，策略可配置）。
 *
 * @author cxxl
 */
public interface RepeatNoiseMarker {

    /**
     * 识别并打标记。
     *
     * @param document 统一文档（就地写 marks）
     * @return 统计与告警
     */
    MarkOutcome mark(UnifiedDocument document);
}
