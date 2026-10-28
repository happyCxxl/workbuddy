package com.workbuddy.common.domain.parse.capability;

import com.workbuddy.common.domain.parse.BBox;
import lombok.Data;

/**
 * 表格单元格（结构识别结果单元）：行列号 + 合并跨度 + 文本 + 表头标记 + 格级包围盒与置信度。
 */
@Data
public class TableCell {

    /** 行号（从 0 起） */
    private Integer row;

    /** 列号（从 0 起） */
    private Integer col;

    /** 行跨度 */
    private Integer rowSpan;

    /** 列跨度 */
    private Integer colSpan;

    /** 单元格文本 */
    private String text;

    /** 是否表头 */
    private Boolean isHeader;

    /** 单元格包围盒（pt，左上原点；结构模型按格给框） */
    private BBox bbox;

    /** 单元格置信度（0~1；模型不提供时为 null） */
    private Double confidence;
}
