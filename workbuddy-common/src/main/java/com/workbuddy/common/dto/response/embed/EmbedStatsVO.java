package com.workbuddy.common.dto.response.embed;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.workbuddy.common.dto.response.task.StageStatsVO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 向量化环节统计：集合表聚合的记录数与缓存命中数。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EmbedStatsVO extends StageStatsVO {

    /** 向量记录数 */
    private Integer recordCount;

    /** 命中缓存的记录数 */
    private Integer cachedCount;
}
