package com.workbuddy.common.dto.response.chunk;

import lombok.Data;

import java.util.Map;

/**
 * 切片统计：最新切片集合的汇总指标（展示用）。
 *
 * @author cxxl
 */
@Data
public class ChunkSummaryVO {

    /** 切片总数 */
    private Integer chunkCount;

    /** 父片数（contentType=SECTION） */
    private Integer parentChunkCount;

    /** 子片数（parentChunkId 非空；无章节归属的孤儿片不计入） */
    private Integer childChunkCount;

    /** 总字符数 */
    private Integer totalChars;

    /** 平均字符数（四舍五入取整） */
    private Integer avgChars;

    /** 内容类型分布（ChunkContentType 枚举名 → 片数） */
    private Map<String, Integer> typeCounts;
}
