package com.workbuddy.common.dto.response.preprocess;

import com.workbuddy.common.dto.response.task.StageDetailVO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 预处理详情 VO：工作台"预处理详情"页数据源。
 * 注：策略版本按运行记录展示（RunRecordVO.strategyVersion），不进详情。
 *
 * @author cxxl
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PreprocessDetailVO extends StageDetailVO {

    /** 预处理统计（从派生视图推导；无产物时为空） */
    private PreprocessSummaryVO summary;

    /** 视图元素（按阅读顺序；无产物时为空列表） */
    private List<PreprocessElementVO> elements;
}
