package com.workbuddy.common.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 向量记录（kb_embedding_record）：一个切片一条；向量本体只进集合文件，不进 DB。
 * 机器表：append-only 仅 create_time（与 kb_chunk 同口径）。
 *
 * @author cxxl
 */
@Data
@TableName("kb_embedding_record")
public class KbEmbeddingRecord {

    /** 主键 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属集合（kb_embedding_set.id） */
    private Long embeddingSetId;

    /** 记录 ID（"emb-%04d"，集合内唯一） */
    private String embeddingId;

    /** 切片 ID（Chunk.chunkId） */
    private String chunkId;

    /** 切片内容类型（ChunkContentType 枚举名） */
    private String contentType;

    /** 父片 ID（扩展预留） */
    private String parentChunkId;

    /** 编码输入文本（= chunk.content 原样） */
    private String inputText;

    /** 输入文本指纹（sha256 hex） */
    private String inputTextHash;

    /** Token 估算 */
    private Integer tokenCount;

    /** 网关 requestId（复用命中时为空） */
    private String requestId;

    /** 状态（SUCCESS/CACHED/SKIPPED/FAILED） */
    private String status;

    /** 是否账本复用命中 */
    private Boolean cacheHit;

    /** 创建时间（DB 默认填充） */
    private LocalDateTime createTime;
}
