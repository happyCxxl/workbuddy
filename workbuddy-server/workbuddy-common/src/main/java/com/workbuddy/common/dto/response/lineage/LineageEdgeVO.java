package com.workbuddy.common.dto.response.lineage;

import lombok.Data;

/**
 * 执行树血缘边：上游任务 → 下游任务。
 * 边来源：下游任务 upstreamProductId → 产物行 → 反查产出该产物的任务（task.productId）。
 */
@Data
public class LineageEdgeVO {

    /** 上游任务 ID（雪花转字符串） */
    private Long fromTaskId;

    /** 下游任务 ID（雪花转字符串） */
    private Long toTaskId;
}
