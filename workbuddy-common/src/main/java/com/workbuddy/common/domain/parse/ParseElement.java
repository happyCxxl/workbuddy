package com.workbuddy.common.domain.parse;

import com.workbuddy.common.enums.parse.ElementType;
import lombok.Data;

import java.util.List;

/**
 * 解析元素（最小单元）：文本元素带 text，图片元素带 assetRef；
 * page/bbox/font/confidence 为组装环节阅读顺序、续表检测、标题层级推定的输入。
 */
@Data
public class ParseElement {

    /** 路内元素 ID：PDF 由页内 ID 分配点统一补（`前缀+页码+页内序号+位置`），Office 解析器直接给；组装环节换全局 ID */
    private String id;

    /** 元素类型（ElementType 枚举名） */
    private String type;

    /** 元素文本（文本类元素非空） */
    private String text;

    /** 段落样式名（DOCX pStyle，如 Heading1；标题三级级联第①层输入） */
    private String style;

    /** 工作表名（XLS/XLSX TABLE 元素；组装环节 sheet→SECTION 输入） */
    private String sheetName;

    /** 图片资产引用（IMAGE 元素专用） */
    private String assetRef;

    /** 页码（PDF 元素；Office 结构路径元素为空；Excel 为 sheet 序号） */
    private Integer page;

    /** 边界框（pt；Office 元素可空） */
    private BBox bbox;

    /** 字体事实（字号/加粗/字体名；无样式标题也输出，层级归组装环节） */
    private FontInfo font;

    /** 置信度（预留，OCR 接入后使用） */
    private Double confidence;

    /** 需 OCR 标记（图片文字未识别；OCR 预留） */
    private Boolean needsOcr;

    /** 表格页尾截断标记（跨页接续判定归组装环节） */
    private Boolean cutAtPageBottom;

    /** 表头重复标记（续表新页开头重复表头；跨页续表判定写入，供组装环节使用） */
    private Boolean headerRepeated;

    /** 表格行数（TABLE 元素专用） */
    private Integer rows;

    /** 表格列数（TABLE 元素专用） */
    private Integer cols;

    /** 表头行号（TABLE 元素专用，从 0 起；无表头为 null） */
    private Integer headerRow;

    /** 单元格（TABLE 元素的子元素，类型 TABLE_CELL） */
    private List<ParseElement> cells;

    /** 单元格行号（TABLE_CELL 专用，从 0 起） */
    private Integer row;

    /** 单元格列号（TABLE_CELL 专用，从 0 起） */
    private Integer col;

    /** 行跨度（TABLE_CELL 专用；Office 合并单元格） */
    private Integer rowSpan;

    /** 列跨度（TABLE_CELL 专用；Office 合并单元格） */
    private Integer colSpan;

    /** 是否表头单元格（TABLE_CELL 专用） */
    private Boolean isHeader;

    /** 目录行级特征（编号模式 + 点线引导符 + 行尾页码；整页聚合判定归预处理环节） */
    private Boolean tocCandidate;

    /** 原文定位信息 */
    private Provenance provenance;

    /**
     * 按给定 ID 造元素。
     *
     * @param id   元素 ID（可空）
     * @param type 元素类型
     * @return 未填充其它属性的元素
     */
    public static ParseElement of(String id, ElementType type) {
        ParseElement element = new ParseElement();
        element.id = id;
        element.type = type.name();
        return element;
    }

    /**
     * 不带 ID 的元素：ID 由产出路径的分配点统一补（PDF 走 {@code PageElementIds}，组装环节换全局 ID）。
     *
     * @param type 元素类型
     * @return 未分配 ID 的元素
     */
    public static ParseElement of(ElementType type) {
        return of(null, type);
    }
}
