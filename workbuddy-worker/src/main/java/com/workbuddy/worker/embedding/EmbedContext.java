package com.workbuddy.worker.embedding;

import com.workbuddy.common.domain.chunk.ChunkSet;
import com.workbuddy.common.domain.embed.EmbeddingSet;
import com.workbuddy.worker.chunking.ChunkProperties;
import com.workbuddy.worker.chunking.strategy.ChunkStrategy;
import com.workbuddy.worker.embedding.strategy.EmbedStrategy;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 向量化上下文：上游切片产物（只读）+ 策略快照（触发时固定）+ 参数配置 + 复用候选账本（新→旧）。
 * 复用候选账本由 biz 侧加载（同 fileResultId + 同策略 + 成功态，回溯上限内），worker 管线只做查表复用，不碰 DB/产物存储。
 * 切片策略快照（chunkStrategy/chunkProperties）供前置校验推算切片最大片长。
 *
 * @author cxxl
 */
@Data
public class EmbedContext {

    /** 上游切片产物（ChunkSet，只读） */
    private ChunkSet chunkSet;

    /** 上游切片集合引用（wb_knowledge_chunk_set.id，血缘回填集合头） */
    private Long chunkSetRef;

    /** 切片策略快照（CHUNK 产物 capabilitySnapshot 解析；供窗口兼容校验推算最大片长，可空=跳过该校验） */
    private ChunkStrategy chunkStrategy;

    /** 切片参数（推算片长上界的默认值来源） */
    private ChunkProperties chunkProperties;

    /** 向量化策略快照（触发时固定；空则管线回退内置默认） */
    private EmbedStrategy strategy;

    /** 向量化参数（knowledge.embed 前缀，application.yml 同名 kebab-case 键） */
    private EmbedProperties properties;

    /** 文件结果 ID（wb_file_result.id） */
    private Long fileResultId;

    /** 复用候选账本：同文件同策略历史 EmbeddingSet（createTime 新→旧，回溯上限内；空=首次运行全量算） */
    private List<EmbeddingSet> reuseCandidates = new ArrayList<>();
}
