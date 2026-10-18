package com.workbuddy.worker.chunking.impl.body;

import com.workbuddy.common.enums.chunk.ChunkAlgorithm;
import org.springframework.stereotype.Component;

/**
 * 正文切片器（结构混合：标题边界 + 段落聚合）：结算逻辑与段落聚合一致（缓冲累计 ≥ targetMaxLen 结算、
 * 单元素超 softMaxLen 递归降级），唯一差异是表格/图片不打断正文组（由管线 flush 规则矩阵控制）。
 * 复用 {@link ParagraphSliceStrategy} 的全部行为，仅声明不同的算法键与能力名。
 */
@Component
public class StructureHybridSliceStrategy extends ParagraphSliceStrategy {

    @Override
    public ChunkAlgorithm algorithm() {
        return ChunkAlgorithm.BODY_STRUCTURE_HYBRID;
    }
}
