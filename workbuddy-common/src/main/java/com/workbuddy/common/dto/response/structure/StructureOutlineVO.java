package com.workbuddy.common.dto.response.structure;

import lombok.Data;

import java.util.List;

/**
 * 文档内容大纲 VO：统一文档元素按阅读顺序（组装结果预览）。
 *
 * @author cxxl
 */
@Data
public class StructureOutlineVO {

    /** 元素 ID */
    private String elementId;

    /** 元素类型（UnifiedElementType 枚举名） */
    private String type;

    /** 元素文本（文本类元素非空） */
    private String text;

    /** 标题层级（TITLE 专属；1 起） */
    private Integer level;

    /** 单页元素：所在页 */
    private Integer page;

    /** 跨页元素：页码范围 */
    private List<Integer> pageRange;

    /** 表格行数（TABLE 专属） */
    private Integer rows;

    /** 表格列数（TABLE 专属） */
    private Integer cols;

    /** 冲突状态（PRIMARY/BACKUP；无冲突为空） */
    private String conflictStatus;

    /** 图注（IMAGE 专属） */
    private String caption;

    /** 表格单元格（TABLE 专属） */
    private List<StructureCellVO> cells;
}
