package com.workbuddy.worker.chunking.impl.fallback;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.worker.chunking.slice.FallbackSlicer;
import com.workbuddy.common.enums.chunk.ChunkAlgorithm;
import com.workbuddy.worker.chunking.strategy.ChunkRouteConfig;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 不兜底切片：超长文本原样单片返回（切片标 FALLBACK + fallbackReason）。
 *
 * @author cxxl
 */
@Component
public class NoneFallback implements FallbackSlicer {

    @Override
    public ChunkAlgorithm algorithm() {
        return ChunkAlgorithm.FALLBACK_NONE;
    }

    @Override
    public List<String> slice(String text, ChunkRouteConfig config) {
        return StrUtil.isBlank(text) ? List.of() : List.of(text);
    }
}
