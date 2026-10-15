package com.workbuddy.worker.retrieval;

import com.workbuddy.common.utils.NullUtil;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF（Reciprocal Rank Fusion）融合纯函数（检索引擎）：
 * score(chunkId) = Σ_c 1/(k + rank_c)，rank 从 1 起（跨通道贡献求和，同名 chunkId 合并）；
 * 按融合分降序。纯函数、无依赖；WEIGHTED 融合预留同包扩展。
 *
 * @author cxxl
 */
public final class RrfFusion {

    private RrfFusion() {
    }

    /** 融合命中（chunkId + 融合分） */
    public record FusedHit(String chunkId, double score) {
    }

    /**
     * 各通道名次升序的 chunkId 列表 → 融合排序。
     */
    public static List<FusedHit> fuse(List<List<String>> rankedChannels, int k) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (List<String> ranked : rankedChannels) {
            if (NullUtil.isNull(ranked)) {
                continue;
            }
            for (int i = 0; i < ranked.size(); i++) {
                double score = 1.0 / (k + i + 1);
                scores.merge(ranked.get(i), score, Double::sum);
            }
        }
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(entry -> new FusedHit(entry.getKey(), entry.getValue()))
                .toList();
    }
}
