package com.workbuddy.common.domain.parse;

import com.workbuddy.common.domain.task.StageOutcome;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 解析产出（ParsePipeline 返回值）：结果封装 + 单元统计 + 状态壳（继承 StageOutcome）。
 * biz 据此写产物存储、落库并回写任务状态。
 * 质量告警明细在 ParseResult.quality（与结果本体一起落产物），本类 warnings 不消费。
 *
 * @author cxxl
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ParseOutcome extends StageOutcome {

    /** 解析结果封装（写产物存储的 JSON 本体） */
    private ParseResult parseResult;

    /** 判定单元总数（PDF 页数 / Excel sheet 数 / Word 按 officePageChars 折算的虚拟单元数） */
    private int unitCount;

    /** 失败单元数（参与 90% 门槛） */
    private int failedUnits;
}
