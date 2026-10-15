package com.workbuddy.common.enums.chunk;

/**
 * 切片内容类型：父片（SECTION）与四路切片产物。
 *
 * @author cxxl
 */
public enum ChunkContentType {

    /** 章节级父片（父子层级，供上下文扩展；向量化由策略开关控制，默认关） */
    SECTION,

    /** 正文段落/列表片 */
    PARAGRAPH,

    /** 表格行/行组片（表头随片） */
    TABLE,

    /** 图片图注占位片 */
    IMAGE,

    /** 兜底片（递归/固定长度降级，带 fallbackReason） */
    FALLBACK
}
