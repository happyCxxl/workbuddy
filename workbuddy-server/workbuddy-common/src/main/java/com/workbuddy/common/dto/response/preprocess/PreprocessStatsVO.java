package com.workbuddy.common.dto.response.preprocess;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.workbuddy.common.dto.response.task.StageStatsVO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

/**
 * 预处理环节统计：元素处置分布、字段规范化、步进聚合与耗时。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PreprocessStatsVO extends StageStatsVO {

    /** step_log 聚合的匹配数 */
    private Long matched;

    /** step_log 聚合的改动数 */
    private Long changed;

    /** 视图元素数 */
    private Integer elementCount;

    /** 保留（进入检索内容流）的元素数 */
    private Integer retainedCount;

    /** 保留比例（百分比整数） */
    private Integer retentionPercent;

    /** 被剔除的元素数 */
    private Integer excludedCount;

    /** 重复份元素数 */
    private Integer repeatedCount;

    /** 不进入切片的元素数 */
    private Integer chunkSkippedCount;

    /** 仅标记元素数 */
    private Integer markedCount;

    /** 标准化字段数 */
    private Integer fieldCount;

    /** 文本改写的元素数 */
    private Integer changedCount;

    /** 编码类规则命中的元素数 */
    private Integer encodingCount;

    /** 整理类规则命中的元素数 */
    private Integer tidyCount;

    /** 处置状态分布（状态名 → 元素数） */
    private Map<String, Integer> statusCounts;

    /** 字段类型分布（字段类型 → 元素数） */
    private Map<String, Integer> fieldTypeCounts;

    /** 受影响范围的起始页 */
    private Integer pageFrom;

    /** 受影响范围的结束页 */
    private Integer pageTo;

    /** 本次运行耗时（毫秒；起止时间缺失时为空） */
    private Long durationMs;
}
