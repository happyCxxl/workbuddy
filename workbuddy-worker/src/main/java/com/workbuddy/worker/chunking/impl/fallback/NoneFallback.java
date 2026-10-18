package com.workbuddy.worker.chunking.impl.fallback;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.worker.chunking.slice.FallbackSlicer;
import com.workbuddy.common.enums.chunk.ChunkAlgorithm;
import com.workbuddy.worker.chunking.strategy.ChunkRouteConfig;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 不兜底切片：超长文本原样单片返回（切片标 FALLBACK + fallbackReason）。
 */
@Component
public class NoneFallback implements FallbackSlicer {

    /**
     * 本策略对应的兜底算法。
     *
     * @return 不兜底算法
     */
    @Override
    public ChunkAlgorithm algorithm() {
        return ChunkAlgorithm.FALLBACK_NONE;
    }

    /**
     * 不切分：空白文本返回空片，其余原样单片返回。
     *
     * @param text   待切分文本
     * @param config 兜底路由配置（本实现不使用）
     * @return 切片文本列表
     */
    @Override
    public List<String> slice(String text, ChunkRouteConfig config) {
        return StrUtil.isBlank(text) ? List.of() : List.of(text);
    }
}
