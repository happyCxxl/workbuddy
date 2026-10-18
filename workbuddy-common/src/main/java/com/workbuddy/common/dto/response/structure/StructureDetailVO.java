package com.workbuddy.common.dto.response.structure;

import com.workbuddy.common.dto.response.task.StageDetailVO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 组装详情 VO：工作台"组装详情"页数据源。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class StructureDetailVO extends StageDetailVO {

    /** 组装统计（从统一文档产物推导；无产物时为空） */
    private StructureSummaryVO summary;

    /** 质量告警（从产物 JSON 提取；无产物时为空列表） */
    private List<String> warnings;

    /** 冲突记录（PRIMARY/BACKUP 并存方案；无产物时为空列表） */
    private List<StructureConflictVO> conflicts;

    /** 文档内容大纲（元素按阅读顺序全量，含标题/段落/表格等；无产物时为空列表） */
    private List<StructureOutlineVO> outline;
}
