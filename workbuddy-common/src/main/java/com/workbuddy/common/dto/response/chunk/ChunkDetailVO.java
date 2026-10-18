package com.workbuddy.common.dto.response.chunk;

import com.workbuddy.common.dto.response.task.StageDetailVO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 切片详情 VO：工作台"切片详情"页数据源。
 * 注：策略版本按运行记录展示（RunRecordVO.strategyVersion），不进详情。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ChunkDetailVO extends StageDetailVO {

    /** 切片统计（最新切片集合推导；无集合时为空） */
    private ChunkSummaryVO summary;

    /** 切片列表（集合内顺序；无集合时为空列表） */
    private List<ChunkItemVO> chunks;
}
