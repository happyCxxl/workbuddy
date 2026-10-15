package com.workbuddy.worker.preprocessing;

import com.workbuddy.worker.preprocessing.strategy.PreprocessStrategy;

import com.workbuddy.common.domain.structure.UnifiedDocument;
import lombok.Data;

/**
 * 预处理上下文：统一文档（只读，底片）+ 策略快照（触发时固定）+ 阈值配置。
 * fileResultId/upstreamProductRef 由 biz 注入，回填视图头（血缘）。
 *
 * @author cxxl
 */
@Data
public class PreprocessContext {

    /** 统一文档（只读，零改动） */
    private UnifiedDocument document;

    /** 预处理策略快照（触发时固定；空则管线回退内置默认） */
    private PreprocessStrategy strategy;

    /** 阈值配置（knowledge.preprocess 前缀，application.yml 同名 kebab-case 键） */
    private PreprocessProperties properties;

    /** 文件结果 ID（kb_file_result.id） */
    private Long fileResultId;

    /** 上游产物引用（STRUCTURE 产物 ID） */
    private Long upstreamProductRef;
}
