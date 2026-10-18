package com.workbuddy.common.domain.chunk;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 切片：检索命中的最小单元。content = 预处理环节 normalizedText 口径；
 * sourceElementIds 继承组装环节元素溯源链；chunkId 集合内顺序号（跨策略不承诺稳定）。
 */
@Data
public class Chunk {

    /** 切片 ID（"chunk-%04d"，集合内唯一） */
    private String chunkId;

    /** 父片 ID（父子层级；本片为父片时为空） */
    private String parentChunkId;

    /** 切片内容（normalizedText 口径，用于索引与向量化） */
    private String content;

    /** 内容类型（ChunkContentType 枚举名） */
    private String contentType;

    /** 标题路径（" > " 连接，层级上限由策略 titlePathMaxLevel 控制，默认 3 级） */
    private String titlePath;

    /** 来源元素 ID（组装环节元素，继承溯源链） */
    private List<String> sourceElementIds = new ArrayList<>();

    /** 页码范围（JSON 为数组；落库转 "1-3" 字符串） */
    private List<Integer> pageRange;

    /** 表格引用（组装环节表元素 ID，表格片专用） */
    private String tableRef;

    /** 文档内顺序（集合内从 1 起） */
    private int order;

    /** 字符数 */
    private int charCount;

    /** Token 估算（ceil(字符数 ÷ 1.5)） */
    private int tokenCount;

    /** 兜底原因（递归/固定长度降级切片时标记，可空） */
    private String fallbackReason;
}
