package com.workbuddy.worker.chunking.impl.fallback;

import com.workbuddy.worker.chunking.WindowSlicer;
import com.workbuddy.common.enums.chunk.ChunkAlgorithm;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 固定窗口兜底切片：忽略语义边界，纯窗口 len + 重叠 overlap 硬切（评测基线口径）。
 */
@Component
public class FixedWindowFallback extends AbstractFallbackSlicer {

    /**
     * 本策略对应的兜底算法。
     *
     * @return 固定窗口算法
     */
    @Override
    public ChunkAlgorithm algorithm() {
        return ChunkAlgorithm.FALLBACK_FIXED_WINDOW;
    }

    /**
     * 按窗口长度与重叠硬切，忽略语义边界。
     *
     * @param text    非空文本
     * @param len     窗口长度
     * @param overlap 重叠长度
     * @return 切片文本列表
     */
    @Override
    protected List<String> doSlice(String text, int len, int overlap) {
        return WindowSlicer.slice(text, len, overlap);
    }
}
