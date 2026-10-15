package com.workbuddy.worker.chunking.slice;

import com.workbuddy.common.domain.chunk.Chunk;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.common.enums.chunk.ChunkAlgorithm;
import com.workbuddy.worker.chunking.SliceContext;
import com.workbuddy.worker.chunking.impl.SliceStrategyRegistry;

import java.util.List;

/**
 * 切片器（策略模式核心）：每路（正文/表格/图片）× 算法（ChunkAlgorithm）一个实现。
 * 注册表 {@link SliceStrategyRegistry} 按算法枚举索引；新增算法 = 新增一个 @Component 实现，管线零改动。
 * 参数从 context.getStrategy() 的路由配置读取（解析时已补默认值），切片器保持无状态（确定性）。
 * 子步骤 capability 由 {@link #algorithm()} 的键推导（见 ChunkPipeline 步骤日志）。
 *
 * @author cxxl
 */
public interface SliceStrategy {

    /**
     * 算法（见 {@link ChunkAlgorithm} 目录）。
     */
    ChunkAlgorithm algorithm();

    /**
     * 切分单个元素。
     *
     * @param element 视图元素（检索文本口径）
     * @param context 切片上下文（含结构索引、当前章节路径、策略与兜底切片器）
     */
    List<Chunk> slice(ViewElement element, SliceContext context);

    /**
     * 结算缓冲（文档遍历结束时调用；跨元素聚合的切片器在此产出残余片）。
     * 单元素切片器无需实现（默认空）。
     */
    default List<Chunk> flush(SliceContext context) {
        return List.of();
    }
}
