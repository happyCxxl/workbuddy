package com.workbuddy.common.dto.response.structure;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.workbuddy.common.dto.response.task.StageStatsVO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 组装环节统计：元素构成、层级、溯源覆盖、续表与重复噪声。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StructureStatsVO extends StageStatsVO {

    /** 元素总数 */
    private Integer elementCount;

    /** 标题数 */
    private Integer titleCount;

    /** 章节数 */
    private Integer chapterCount;

    /** 一级标题数 */
    private Integer level1Count;

    /** 二级标题数 */
    private Integer level2Count;

    /** 三级及以下标题数 */
    private Integer level3PlusCount;

    /** 父子关系数 */
    private Integer parentChildCount;

    /** 续表关系数 */
    private Integer continuationCount;

    /** 疑似续表数 */
    private Integer suspectedContinuationCount;

    /** 已溯源元素数 */
    private Integer tracedCount;

    /** 溯源覆盖率（百分比整数） */
    private Integer provenanceCoverage;

    /** 无坐标元素数 */
    private Integer withoutBboxCount;

    /** 重复段数 */
    private Integer repeatedSegmentCount;

    /** 重复页数 */
    private Integer repeatedPageCount;

    /** 噪声页数 */
    private Integer noisePageCount;

    /** 页数 */
    private Integer pageCount;

    /** 续表起始页 */
    private Integer continuationFromPage;

    /** 续表结束页 */
    private Integer continuationToPage;

    /** 产物 schema 版本 */
    private String schemaVersion;

    /** 本次运行耗时（毫秒；起止时间缺失时为空） */
    private Long durationMs;
}
