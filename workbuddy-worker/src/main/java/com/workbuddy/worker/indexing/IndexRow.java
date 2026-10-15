package com.workbuddy.worker.indexing;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 索引写入行：一条可检索向量记录（向量化记录的可检索子集，SUCCESS/CACHED）。
 *
 * @author cxxl
 */
@Data
public class IndexRow implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 片 ID（关联键） */
    private String chunkId;

    /** 文件结果 ID */
    private Long documentId;

    /** 所有者 */
    private String owner;

    /** 片类型（PARAGRAPH/TABLE/SECTION…） */
    private String contentType;

    /** 父片 ID */
    private String parentChunkId;

    /** 片文本（全文通道 + 返回字段） */
    private String content;

    /** 标题路径（全文参与 + 返回） */
    private String titlePath;

    /** 源元素 ID 列表（JSON 串，返回） */
    private String sourceElementIds;

    /** 向量本体（维度随组合 EMBED 策略，非固定 1024） */
    private List<Float> vector;
}
