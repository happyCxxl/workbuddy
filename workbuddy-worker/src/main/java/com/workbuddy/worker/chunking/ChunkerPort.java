package com.workbuddy.worker.chunking;

import com.workbuddy.common.domain.chunk.ChunkOutcome;

/**
 * 切片接口：把预处理视图（检索文本口径）按"结构优先 + 招投标行规"切成片段集合。
 * 输入视图与组装环节原结构只读零改动；策略快照由上下文携带（触发时固定）。
 *
 * @author cxxl
 */
public interface ChunkerPort {

    /**
     * 执行切片。
     *
     * @param context 切片上下文（视图 + 原结构 + 策略快照 + 配置）
     * @return ChunkSet 与子步骤记录；状态建议（SUCCESS/PARTIAL_SUCCESS/FAILED）
     */
    ChunkOutcome chunk(ChunkContext context);
}
