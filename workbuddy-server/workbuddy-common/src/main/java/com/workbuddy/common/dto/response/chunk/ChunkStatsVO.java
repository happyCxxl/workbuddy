package com.workbuddy.common.dto.response.chunk;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.workbuddy.common.dto.response.task.StageStatsVO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 切片环节统计：片数、类型分布、片长、路由与策略参数。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChunkStatsVO extends StageStatsVO {

    /** 切片总数（含父片） */
    private Integer chunkCount;

    /** 父片数 */
    private Integer sectionCount;

    /** 正文片数 */
    private Integer paragraphCount;

    /** 表格片数 */
    private Integer tableCount;

    /** 图片片数 */
    private Integer imageCount;

    /** 兜底片数 */
    private Integer fallbackCount;

    /** 父片数（不产向量的 SECTION 片） */
    private Integer parentCount;

    /** 孤儿片数 */
    private Integer orphanCount;

    /** 全部片字符数合计 */
    private Integer totalChars;

    /** 平均片字符数 */
    private Integer avgChars;

    /** 最长片字符数 */
    private Integer maxChars;

    /** 超过目标上限的片数 */
    private Integer overTargetMaxCount;

    /** 超过软上限的片数 */
    private Integer overSoftMaxCount;

    /** 最小合并长度 */
    private Integer minMergeLen;

    /** 低于最小合并长度的片数 */
    private Integer underMinMergeCount;

    /** 进入切片的元素数 */
    private Integer routedElementCount;

    /** 被跳过（预处理剔除与重复份）的元素数 */
    private Integer skippedElementCount;

    /** 来源元素多于 1 个的片数 */
    private Integer multiSourceChunkCount;

    /** 平均来源元素数 */
    private Integer avgSourceElementCount;

    /** 目标片长 */
    private Integer targetMaxLen;

    /** 软上限片长 */
    private Integer softMaxLen;

    /** 兜底片长 */
    private Integer fallbackLen;

    /** 兜底重叠长度 */
    private Integer fallbackOverlap;

    /** 本次运行耗时（毫秒；起止时间缺失时为空） */
    private Long durationMs;
}
