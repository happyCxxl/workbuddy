package com.workbuddy.common.dto.response.structure;

import lombok.Data;

/**
 * 表格单元格 VO（文档内容大纲 TABLE 渲染用）。
 */
@Data
public class StructureCellVO {

    /** 单元格行号 */
    private Integer row;

    /** 单元格列号 */
    private Integer col;

    /** 单元格文本 */
    private String text;

    /** 是否表头单元格 */
    private Boolean isHeader;
}
