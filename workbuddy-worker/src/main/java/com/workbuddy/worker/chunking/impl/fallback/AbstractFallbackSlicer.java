package com.workbuddy.worker.chunking.impl.fallback;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.worker.chunking.slice.FallbackSlicer;
import com.workbuddy.worker.chunking.strategy.ChunkParamKeys;
import com.workbuddy.worker.chunking.strategy.ChunkRouteConfig;

import java.util.List;

/**
 * 窗口类兜底切片器骨架：空文本返回空片，其余从 fallback 路由读 len/overlap 交给 doSlice。
 */
public abstract class AbstractFallbackSlicer implements FallbackSlicer {

    @Override
    public final List<String> slice(String text, ChunkRouteConfig config) {
        if (StrUtil.isBlank(text)) {
            return List.of();
        }
        return doSlice(text, config.intParam(ChunkParamKeys.LEN, 500), config.intParam(ChunkParamKeys.OVERLAP, 50));
    }

    /**
     * 切分非空文本。
     *
     * @param text    非空文本
     * @param len     窗口长度（字符，解析时已补默认）
     * @param overlap 重叠长度（字符，仅本路使用）
     */
    protected abstract List<String> doSlice(String text, int len, int overlap);
}
