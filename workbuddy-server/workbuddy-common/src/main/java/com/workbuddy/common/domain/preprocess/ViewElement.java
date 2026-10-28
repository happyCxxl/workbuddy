package com.workbuddy.common.domain.preprocess;

import com.workbuddy.common.domain.parse.Provenance;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 派生视图元素：对应一个 UnifiedElement 的派生副本。
 * rawText 永为原文；处置状态（status）只落在本副本，原结构树不变。
 */
@Data
public class ViewElement {

    /** 源元素 ID（UnifiedElement.id，血缘锚点） */
    private String elementId;

    /** 元素类型（UnifiedElementType 枚举名） */
    private String type;

    /** 处置状态（ViewElementStatus 枚举名） */
    private String status;

    /** 源元素标记（ElementMark 枚举名；识别环节写入，供处置规则读取与审计） */
    private List<String> marks;

    /** 所在页（单页元素；Word 段落为空） */
    private Integer page;

    /** 原始文本（永久保留，不修改） */
    private String rawText;

    /** 展示文本（仅整理空白、换行和明显乱码） */
    private String displayText;

    /** 检索文本（字符级规范化 + 字段标准化；被剔除态为 null 表示不进内容流） */
    private String normalizedText;

    /** 标准化字段（金额/日期/面积/证书号） */
    private List<NormalizedField> normalizedFields = new ArrayList<>();

    /** 处理轨迹（命中规则 + 前后摘要） */
    private List<TraceEntry> preprocessTrace = new ArrayList<>();

    /** 表格单元格（TABLE 元素专用） */
    private List<ViewCell> cells;

    /** 原文定位信息 */
    private Provenance provenance;
}
