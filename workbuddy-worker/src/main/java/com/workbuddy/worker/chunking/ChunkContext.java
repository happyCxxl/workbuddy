package com.workbuddy.worker.chunking;

import com.workbuddy.common.domain.preprocess.PreprocessView;
import com.workbuddy.common.domain.structure.UnifiedDocument;
import com.workbuddy.worker.chunking.strategy.ChunkStrategy;
import lombok.Data;

/**
 * 切片上下文：预处理视图（检索文本口径）+ 统一文档（结构参照：标题层级/图注/表格行）+
 * 策略快照（触发时固定）+ 参数配置。fileResultId/upstreamProductRef 由 biz 注入回填集合头。
 *
 * @author cxxl
 */
@Data
public class ChunkContext {

    /** 预处理视图（只读） */
    private PreprocessView view;

    /** 统一文档（只读，结构参照） */
    private UnifiedDocument document;

    /** 切片策略快照（触发时固定；空则管线回退内置默认） */
    private ChunkStrategy strategy;

    /** 切片参数（knowledge.chunk 前缀，application.yml 同名 kebab-case 键） */
    private ChunkProperties properties;

    /** 文件结果 ID（wb_file_result.id） */
    private Long fileResultId;

    /** 上游产物引用（PREPROCESS 视图产物 ID） */
    private Long upstreamProductRef;
}
