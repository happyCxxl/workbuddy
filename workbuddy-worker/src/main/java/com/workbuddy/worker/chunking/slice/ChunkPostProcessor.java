package com.workbuddy.worker.chunking.slice;

import com.workbuddy.common.domain.chunk.Chunk;
import com.workbuddy.worker.chunking.SliceContext;

import java.util.List;

/**
 * 切片后置处理器（Pipes-and-Filters）：对每章子片/孤儿片列表做顺序修饰。
 * 注册顺序即执行顺序（@Order）：structureOverlap → minMergeLen → titleInContent。
 * 每步独立实现、独立单测、可插拔；跨章节不生效（管线按章节列表分别调用）。
 *
 * @author cxxl
 */
public interface ChunkPostProcessor {

    /** 就地修饰片列表（按文档序） */
    void process(List<Chunk> chunks, SliceContext context);
}
