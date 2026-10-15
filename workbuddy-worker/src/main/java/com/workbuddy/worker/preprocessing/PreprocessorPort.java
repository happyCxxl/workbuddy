package com.workbuddy.worker.preprocessing;

import com.workbuddy.common.domain.preprocess.PreprocessOutcome;

/**
 * 预处理接口：对统一文档（只读）执行八步固定顺序处置，产出五层派生视图。
 * 原文 UnifiedDocument 零改动；策略快照由上下文携带（触发时固定）。
 *
 * @author cxxl
 */
public interface PreprocessorPort {

    /**
     * 执行预处理。
     *
     * @param context 预处理上下文（文档 + 策略快照 + 配置）
     * @return 派生视图与子步骤记录；状态建议（SUCCESS/PARTIAL_SUCCESS/FAILED）
     */
    PreprocessOutcome preprocess(PreprocessContext context);
}
