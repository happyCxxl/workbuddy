package com.workbuddy.common.enums.chunk;

/**
 * 流程层设置键：ChunkStrategy.pipeline 映射的键目录。
 *
 * @author cxxl
 */
public enum PipelineKey {

    /** 标题路径保留级数 */
    TITLE_PATH_MAX_LEVEL("titlePathMaxLevel"),

    /** 父子层级（每个章节生成父片） */
    PARENT_CHILD("parentChild"),

    /** 表格并入正文流（与表格路 context-merged 互斥） */
    TABLE_IN_BODY_FLOW("tableInBodyFlow"),

    /** 最小合并长度（碎片合并） */
    MIN_MERGE_LEN("minMergeLen"),

    /** 结构切片重叠 */
    STRUCTURE_OVERLAP("structureOverlap"),

    /** 标题入正文 */
    TITLE_IN_CONTENT("titleInContent");

    private final String key;

    PipelineKey(String key) {
        this.key = key;
    }

    /** 策略快照中的序列化键（JSON 契约） */
    public String key() {
        return key;
    }
}
