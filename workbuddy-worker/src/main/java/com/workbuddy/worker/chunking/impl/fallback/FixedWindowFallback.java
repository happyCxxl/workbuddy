package com.workbuddy.worker.chunking.impl.fallback;

import com.workbuddy.worker.chunking.WindowSlicer;
import com.workbuddy.common.enums.chunk.ChunkAlgorithm;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 固定窗口兜底切片：忽略语义边界，纯窗口 len + 重叠 overlap 硬切（评测基线口径）。
 *
 * @author cxxl
 */
@Component
public class FixedWindowFallback extends AbstractFallbackSlicer {

    @Override
    public ChunkAlgorithm algorithm() {
        return ChunkAlgorithm.FALLBACK_FIXED_WINDOW;
    }

    @Override
    protected List<String> doSlice(String text, int len, int overlap) {
        return WindowSlicer.slice(text, len, overlap);
    }
}
