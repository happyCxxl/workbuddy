package com.workbuddy.worker.chunking.impl;

import com.workbuddy.common.enums.chunk.ChunkAlgorithm;
import com.workbuddy.common.enums.chunk.ChunkRoute;
import com.workbuddy.worker.chunking.slice.FallbackSlicer;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 兜底切片器注册表：按算法索引（AlgorithmRegistry 泛型收口）；
 * 同 SliceStrategyRegistry 的开闭原则与查找失败报错口径。
 */
@Component
public class FallbackSlicerRegistry {

    private final AlgorithmRegistry<FallbackSlicer> registry;

    /**
     * 构造注册表：只收 FALLBACK 路的切片器，重复注册即启动失败。
     *
     * @param slicers 兜底切片器实现
     */
    public FallbackSlicerRegistry(List<FallbackSlicer> slicers) {
        this.registry = new AlgorithmRegistry<>(slicers, FallbackSlicer::algorithm, "兜底切片器",
                slicer -> slicer.algorithm().route() == ChunkRoute.FALLBACK);
    }

    /**
     * 按算法取兜底切片器；未注册明确报错（SYSTEM_ERROR），不静默回退。
     *
     * @param algorithm 兜底算法
     * @return 对应切片器
     */
    public FallbackSlicer get(ChunkAlgorithm algorithm) {
        return registry.get(algorithm);
    }
}
