package com.workbuddy.worker.embedding;

import cn.hutool.core.util.ObjectUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.chunking.ChunkProperties;
import com.workbuddy.worker.chunking.strategy.ChunkStrategy;
import com.workbuddy.worker.chunking.strategy.ChunkWindowEstimator;
import com.workbuddy.worker.embedding.strategy.EmbedStrategy;

/**
 * 向量化窗口兼容规则：切片策略最大片长 ≤ 模型窗口的判定核心
 * （触发前置校验与运行期校验共用）。
 */
public final class EmbedWindowRules {

    private EmbedWindowRules() {
    }

    /**
     * 窗口兼容计算：bound = 切片策略最大片长（字符），allowed = 模型窗口允许字符数；
     * computable=false 表示不可判定（无切片策略快照，或兜底 none 上界不可推算）。
     */
    public static WindowBound resolve(ChunkStrategy chunkStrategy, ChunkProperties chunkProperties,
                                      EmbedStrategy strategy, EmbedProperties embedProperties) {
        if (NullUtil.isNull(chunkStrategy)) {
            return new WindowBound(0, 0, false);
        }
        int bound = ChunkWindowEstimator.maxWindowChars(chunkStrategy, chunkProperties);
        if (bound == Integer.MAX_VALUE) {
            return new WindowBound(bound, 0, false);
        }
        int allowed = (int) Math.ceil(ObjectUtil.defaultIfNull(strategy.getContextWindowTokens(), 0)
                * embedProperties.getWindowCheckFactor());
        return new WindowBound(bound, allowed, true);
    }

    /** 窗口兼容计算产物（bound/allowed 为字符数） */
    public record WindowBound(int bound, int allowed, boolean computable) {
    }
}
