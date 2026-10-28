package com.workbuddy.common.domain.structure;

import com.workbuddy.common.domain.task.StageOutcome;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 组装产出（AssemblerPipeline 返回值）：统一文档 + 组装报告 + 状态壳（继承 StageOutcome）。
 * biz 据此写产物存储、落库并回写任务状态。
 * 质量告警明细在 UnifiedDocument.quality（与文档本体一起落产物），本类 warnings 不消费。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AssembleOutcome extends StageOutcome {

    /** 统一文档（写产物存储的 JSON 本体） */
    private UnifiedDocument document;

    /** 组装报告（统计 + 可回溯占比） */
    private AssembleReport report;
}
