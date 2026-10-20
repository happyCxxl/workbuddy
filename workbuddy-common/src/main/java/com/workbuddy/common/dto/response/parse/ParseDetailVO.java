package com.workbuddy.common.dto.response.parse;

import com.workbuddy.common.dto.response.lineage.LineageParseStatsVO;
import com.workbuddy.common.dto.response.task.StageDetailVO;
import com.workbuddy.common.dto.response.task.StageStatsVO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 解析详情 VO：工作台"解析详情"页数据源。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ParseDetailVO extends StageDetailVO<StageStatsVO> {

    /** 质量告警（从产物 JSON 提取；无产物时为空列表） */
    private List<String> warnings;

    /**
     * 解析运行统计（元素构成/问题单元/耗时）：与执行树节点同一份汇总口径。
     *
     * <p>产物不可读或运行尚无产物时为 null，页面按"缺失"渲染。
     */
    private LineageParseStatsVO parseStats;

    /** 解析结论文案（成功=无异常、部分成功=问题单元数与范围、失败=失败原因）；统计缺失时为 null */
    private String parseSummary;
}
