package com.workbuddy.worker.chunking.slice;

import com.workbuddy.common.enums.chunk.ChunkAlgorithm;
import com.workbuddy.worker.chunking.strategy.ChunkRouteConfig;

import java.util.List;

/**
 * 兜底切片器：正文路超长文本的最后降级手段。
 * 参数从路由配置（fallback 路）读取；同 FallbackSlicerRegistry 按 algorithm 索引。
 *
 * @author cxxl
 */
public interface FallbackSlicer {

    /** 算法（见 ChunkAlgorithm FALLBACK_* 常量） */
    ChunkAlgorithm algorithm();

    /**
     * 超长文本 → 片段列表（每片 ≤ len；按算法口径决定重叠/不切）。
     *
     * @param text   超长文本
     * @param config fallback 路由配置（len/overlap 等参数，解析时已补默认）
     */
    List<String> slice(String text, ChunkRouteConfig config);
}
