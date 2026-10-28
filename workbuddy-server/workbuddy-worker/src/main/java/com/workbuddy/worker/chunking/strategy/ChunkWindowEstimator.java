package com.workbuddy.worker.chunking.strategy;

import com.workbuddy.common.enums.chunk.ChunkAlgorithm;
import com.workbuddy.common.enums.chunk.ChunkRoute;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.chunking.ChunkProperties;

/**
 * 切片策略最大片长推算（向量化环节前置校验用）：
 * 上界 = max(正文路 bound, 表格路 bound=整表 maxLen, 兜底路 bound)；
 * 正文路按所选算法：段落/结构混合/句子聚合 → softMaxLen；标题边界 → maxLen；固定窗口 → len。
 * 兜底算法为 none（原样单片段输出）时上界不可推算 → 返回 {@link Integer#MAX_VALUE}。
 */
public final class ChunkWindowEstimator {

    private ChunkWindowEstimator() {
    }

    /**
     * 推算切片策略可产生的最大片长（字符）；不可推算（兜底 none）返回 {@link Integer#MAX_VALUE}。
     *
     * @param strategy   切片策略
     * @param properties 切片全局默认值
     * @return 最大片长（字符）；不可推算时为 Integer.MAX_VALUE
     */
    public static int maxWindowChars(ChunkStrategy strategy, ChunkProperties properties) {
        if (NullUtil.isNull(strategy)) {
            return Integer.MAX_VALUE;
        }
        int bound = 0;
        ChunkAlgorithm body = strategy.routeAlgorithm(ChunkRoute.BODY);
        if (NullUtil.isNotNull(body)) {
            switch (body) {
                case BODY_PARAGRAPH_AGGREGATE, BODY_STRUCTURE_HYBRID, BODY_SENTENCE_AGGREGATE ->
                        bound = Math.max(bound, paramInt(strategy, ChunkRoute.BODY, ChunkParamKeys.SOFT_MAX_LEN,
                                properties.getSoftMaxLen()));
                case BODY_TITLE_BOUNDARY -> bound = Math.max(bound, paramInt(strategy, ChunkRoute.BODY,
                        ChunkParamKeys.MAX_LEN, properties.getTitleBoundaryMaxLen()));
                case BODY_FIXED_WINDOW -> bound = Math.max(bound, paramInt(strategy, ChunkRoute.BODY,
                        ChunkParamKeys.LEN, properties.getBodyWindowLen()));
                default -> {
                }
            }
        }
        // 表格路上界 = 整表 maxLen（全局配置；行级/行组片长度难推算，按整表上限保守估算）
        bound = Math.max(bound, properties.getWholeTableMaxLen());
        ChunkAlgorithm fallback = strategy.routeAlgorithm(ChunkRoute.FALLBACK);
        if (fallback == ChunkAlgorithm.FALLBACK_NONE) {
            // 不兜底：超长文本原样单片段输出，上界不可推算
            return Integer.MAX_VALUE;
        }
        if (fallback == ChunkAlgorithm.FALLBACK_RECURSIVE || fallback == ChunkAlgorithm.FALLBACK_FIXED_WINDOW) {
            bound = Math.max(bound, paramInt(strategy, ChunkRoute.FALLBACK, ChunkParamKeys.LEN,
                    properties.getRecursiveLen()));
        }
        return bound;
    }

    private static int paramInt(ChunkStrategy strategy, ChunkRoute route, String key, int defaultValue) {
        ChunkRouteConfig config = strategy.route(route);
        if (NullUtil.isNull(config)) {
            return defaultValue;
        }
        return config.intParam(key, defaultValue);
    }
}
