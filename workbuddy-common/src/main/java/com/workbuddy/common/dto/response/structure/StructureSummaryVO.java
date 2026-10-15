package com.workbuddy.common.dto.response.structure;

import lombok.Data;

/**
 * 组装统计 VO：UnifiedDocument 统计指标的对外视图（从统一文档产物推导，展示用）。
 *
 * @author cxxl
 */
@Data
public class StructureSummaryVO {

    /** 元素总数 */
    private Integer elementCount;

    /** 标题元素数 */
    private Integer titleCount;

    /** 段落元素数 */
    private Integer paragraphCount;

    /** 表格元素数 */
    private Integer tableCount;

    /** 图片元素数 */
    private Integer imageCount;

    /** 关系总数 */
    private Integer relationCount;

    /** 冲突数 */
    private Integer conflictCount;

    /** 告警数 */
    private Integer warningCount;
}
