package com.workbuddy.worker.chunking.impl;

import com.workbuddy.common.enums.chunk.ChunkAlgorithm;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.utils.NullUtil;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 算法注册表（工厂，泛型收口）：按 ChunkAlgorithm 建索引；
 * 归属校验（谓词）+ 重复注册报错 + 查找失败报错一次声明，切片器与兜底切片器两个注册表共用。
 * 新增算法 = 新增一个 @Component 实现，自动收录（开闭原则）；查找不到明确报错，不静默回退。
 *
 * @param <T> 注册的算法实现类型（SliceStrategy / FallbackSlicer）
 */
public final class AlgorithmRegistry<T> {

    private final Map<ChunkAlgorithm, T> byAlgorithm = new HashMap<>();
    private final String kindLabel;

    /**
     * 建索引（启动期执行）：候选逐个校验归属并登记，重复/归属不符即启动失败。
     *
     * @param candidates  Spring 注入候选
     * @param algorithmOf 候选 → 算法提取
     * @param kindLabel   注册表种类名（报错文案前缀："切片器"/"兜底切片器"）
     * @param allowed     归属校验（不符即启动失败）
     */
    public AlgorithmRegistry(List<T> candidates, Function<T, ChunkAlgorithm> algorithmOf,
                             String kindLabel, Predicate<T> allowed) {
        this.kindLabel = kindLabel;
        for (T candidate : candidates) {
            ChunkAlgorithm algorithm = algorithmOf.apply(candidate);
            ThrowUtil.throwIf(!allowed.test(candidate), () -> new IllegalStateException(
                    kindLabel + "归属校验失败（算法不应注册到本表）: algorithm=" + algorithm.key()));
            T existed = byAlgorithm.put(algorithm, candidate);
            ThrowUtil.throwIf(NullUtil.isNotNull(existed),
                    () -> new IllegalStateException(kindLabel + "重复注册: algorithm=" + algorithm.key()));
        }
    }

    /** 按算法取实现；未注册明确报错（SYSTEM_ERROR），不静默回退。 */
    public T get(ChunkAlgorithm algorithm) {
        T candidate = NullUtil.isNull(algorithm) ? null : byAlgorithm.get(algorithm);
        ThrowUtil.throwIf(NullUtil.isNull(candidate), ErrorCode.SYSTEM_ERROR,
                kindLabel + "无实现: algorithm=" + (NullUtil.isNull(algorithm) ? "null" : algorithm.key()));
        return candidate;
    }
}
