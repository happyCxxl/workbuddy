package com.workbuddy.common.dto.response.preprocess;

import lombok.Data;

import java.util.List;

/**
 * 视图元素 VO：ViewElement 的对外视图（UnifiedElement 的派生副本：原文/展示/检索三层文本 + 处置状态 + 标准化字段）。
 */
@Data
public class PreprocessElementVO {

    /** 源元素 ID（UnifiedElement.id，血缘锚点） */
    private String elementId;

    /** 元素类型（UnifiedElementType 枚举名） */
    private String type;

    /** 处置状态（ViewElementStatus 枚举名） */
    private String status;

    /** 所在页（单页元素） */
    private Integer page;

    /** 原始文本（永久保留，不修改；前端做"原文→展示→检索"三层对照） */
    private String rawText;

    /** 展示文本（仅整理空白、换行和明显乱码） */
    private String displayText;

    /** 检索文本（字符级规范化；被剔除态为 null 表示不进内容流） */
    private String normalizedText;

    /** 处理轨迹（非 KEEP 条目：规则/动作/前后摘要/证据） */
    private List<PreprocessTraceVO> trace;

    /** 标准化字段（金额/日期/面积/证书号） */
    private List<PreprocessFieldVO> fields;

    /** 表格单元格（TABLE 元素专用） */
    private List<PreprocessCellVO> cells;
}
