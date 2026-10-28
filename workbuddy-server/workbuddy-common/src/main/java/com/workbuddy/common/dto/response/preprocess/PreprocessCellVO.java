package com.workbuddy.common.dto.response.preprocess;

import lombok.Data;

/**
 * 视图单元格 VO：ViewCell 的对外视图（表格单元格的派生副本：原文 text + 检索文本）。
 */
@Data
public class PreprocessCellVO {

    /** 源单元格 ID（UnifiedElement.id） */
    private String cellId;

    /** 原始文本（永久保留） */
    private String text;

    /** 单元格行号（前端表格网格定位用） */
    private Integer row;

    /** 单元格列号（前端表格网格定位用） */
    private Integer col;

    /** 是否表头单元格（前端表头行高亮用） */
    private Boolean isHeader;

    /** 检索文本（被剔除态为 null） */
    private String normalizedText;
}
