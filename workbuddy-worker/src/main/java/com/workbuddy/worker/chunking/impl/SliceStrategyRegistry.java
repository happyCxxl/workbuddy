package com.workbuddy.worker.chunking.impl;

import com.workbuddy.common.enums.chunk.ChunkAlgorithm;
import com.workbuddy.common.enums.chunk.ChunkRoute;
import com.workbuddy.worker.chunking.slice.SliceStrategy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 切片器注册表（工厂）：启动时把 Spring 注入的全部 SliceStrategy 按算法建索引（AlgorithmRegistry 泛型收口）。
 * 新增算法 = 新增一个 @Component 实现，自动收录（开闭原则）；查找不到 → 明确报错，不静默回退。
 *
 * @author cxxl
 */
@Component
public class SliceStrategyRegistry {

    private final AlgorithmRegistry<SliceStrategy> registry;

    public SliceStrategyRegistry(List<SliceStrategy> strategies) {
        this.registry = new AlgorithmRegistry<>(strategies, SliceStrategy::algorithm, "切片器",
                strategy -> strategy.algorithm().route() != ChunkRoute.FALLBACK);
    }

    /** 按算法取实现；未找到明确报错（执行失败而非静默降级） */
    public SliceStrategy get(ChunkAlgorithm algorithm) {
        return registry.get(algorithm);
    }
}
