package com.workbuddy.common.domain.structure;

import com.workbuddy.common.domain.parse.BBox;
import com.workbuddy.common.domain.parse.FontInfo;
import com.workbuddy.common.domain.parse.Provenance;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 统一元素：单文档内唯一、稳定公共字段；解析器特有信息放 extension（下游只依赖公共字段）。
 */
@Data
public class UnifiedElement {

    /** 元素 ID = 来源前缀 + 内容哈希（n-/t-/tc-/i-/h-/s-）；跨处理不承诺稳定 */
    private String id;

    /** 元素类型（UnifiedElementType 枚举名） */
    private String type;

    /** 元素文本（文本类元素非空） */
    private String text;

    /** 图片资产引用（IMAGE 专用） */
    private String assetRef;

    /** 单页元素：所在页 */
    private Integer page;

    /** 单页元素：边界框 */
    private BBox bbox;

    /** 跨页元素：页码范围 */
    private List<Integer> pageRange;

    /** 跨页元素：按页分段框（供预览分段高亮；当前无生产读取方） */
    private List<ElementBBox> bboxes;

    /** 字体事实 */
    private FontInfo font;

    /** 标题层级（TITLE 专属；1 起，越浅越小） */
    private Integer level;

    /** 标题推定证据（TITLE 专属；层级推定可审计） */
    private TitleEvidence titleEvidence;

    /** 表格行数（TABLE 专属） */
    private Integer rows;

    /** 表格列数（TABLE 专属） */
    private Integer cols;

    /** 表头行号（TABLE 专属，从 0 起） */
    private Integer headerRow;

    /** 表头是否继承自续表上游（TABLE 专属） */
    private Boolean headerInherited;

    /** 单元格（TABLE 的子元素，类型 TABLE_CELL） */
    private List<UnifiedElement> cells;

    /** 单元格行号（TABLE_CELL 专用） */
    private Integer row;

    /** 单元格列号（TABLE_CELL 专用） */
    private Integer col;

    /** 行跨度（TABLE_CELL 专用） */
    private Integer rowSpan;

    /** 列跨度（TABLE_CELL 专用） */
    private Integer colSpan;

    /** 是否表头单元格（TABLE_CELL 专用） */
    private Boolean isHeader;

    /** 图注（IMAGE 专用，暂不专门识别） */
    private String caption;

    /** 冲突状态（ConflictStatus 枚举名；无冲突为空） */
    private String conflictStatus;

    /** 元素级标记（ElementMark 枚举名；识别环节写入、处置环节读取） */
    private List<String> marks;

    /** 原文定位信息 */
    private Provenance provenance;

    /** 扩展区：解析器特有字段（如 needsOcr/样式名），下游只依赖公共字段 */
    private Map<String, Object> extension;
}
