package com.workbuddy.common.dto.response.lineage;

import lombok.Data;

/**
 * 解析环节运行统计（执行树节点展示用）：由产物本体的 ParseResult 汇总下发。
 *
 * <p>四类元素计数与总数同口径（总数 = 四类之和 + 其它类型）；某项在产物里取不到时为 null，
 * 由前端按"缺失"渲染，不回落 0。
 */
@Data
public class LineageParseStatsVO {

    /** 页数（判定单元数） */
    private Integer pageCount;

    /** 元素总数 */
    private Integer elementCount;

    /** 正文类元素数（PARAGRAPH/LIST/FIGURE_CAPTION） */
    private Integer bodyCount;

    /** 表格数 */
    private Integer tableCount;

    /** 图片数 */
    private Integer imageCount;

    /** 页眉页脚数 */
    private Integer headerFooterCount;

    /** 质量告警数（产物 quality.warnings 计数） */
    private Integer warningCount;

    /** 未解析出内容的单元数 */
    private Integer failedUnitCount;

    /** 未解析出内容的起始单元号 */
    private Integer failedFrom;

    /** 未解析出内容的结束单元号 */
    private Integer failedTo;

    /** 本次运行耗时（毫秒） */
    private Long durationMs;
}
