package com.workbuddy.common.domain.embed;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 向量记录：一个切片一条。inputText = chunk.content（原样编码口径）；
 * status=CACHED 表示复用历史账本向量本体（cacheHit=true，免网关调用）；向量本体只进集合文件，不进 DB。
 *
 * @author cxxl
 */
@Data
public class EmbeddingRecord {

    /** 记录 ID（"emb-%04d"，集合内唯一） */
    private String embeddingId;

    /** 切片 ID（Chunk.chunkId） */
    private String chunkId;

    /** 切片内容类型（ChunkContentType 枚举名） */
    private String contentType;

    /** 父片 ID（扩展预留：父子检索） */
    private String parentChunkId;

    /** 编码输入文本（= chunk.content 原样） */
    private String inputText;

    /** 输入文本指纹（sha256 hex，复用键之一） */
    private String inputTextHash;

    /** Token 估算（ceil(字符数 ÷ 1.5)） */
    private int tokenCount;

    /** 网关 requestId（成本审计；复用命中时为空） */
    private String requestId;

    /** 状态（EmbedRecordStatus 枚举名：SUCCESS/CACHED/SKIPPED/FAILED） */
    private String status;

    /** 是否账本复用命中 */
    private boolean cacheHit;

    /** 向量本体（SKIPPED/FAILED 时为空） */
    private List<Float> vector = new ArrayList<>();
}
