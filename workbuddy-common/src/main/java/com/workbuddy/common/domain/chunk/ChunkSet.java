package com.workbuddy.common.domain.chunk;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 切片产物集合：一次切片策略运行的产物（多套策略 = 多个集合并存，平行候选）。
 *
 * @author cxxl
 */
@Data
public class ChunkSet {

    /** 集合 ID = "cs-" + documentId + "-" + strategyVersion（确定性） */
    private String chunkSetId;

    /** 文件结果 ID（kb_file_result.id） */
    private Long fileResultId;

    /** 统一文档 ID（UnifiedDocument.documentInfo.documentId） */
    private String documentId;

    /** 切片策略版本（如 chunk-hybrid-v1） */
    private String strategyVersion;

    /** 上游产物引用（PREPROCESS 视图产物 ID） */
    private Long upstreamProductRef;

    /** 切片数 */
    private int chunkCount;

    /** 切片列表（全部父片在前、子片随后的文档顺序） */
    private List<Chunk> chunks = new ArrayList<>();
}
