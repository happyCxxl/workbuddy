package com.workbuddy.common.domain.parse;

import com.workbuddy.common.domain.parse.signal.PageMetric;
import com.workbuddy.common.enums.parse.UnitType;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 单元解析结果：一个单元的内容、位置与单元质量，自包含、可序列化（不携带解析器句柄）。
 * 单元归属由单元序号表达，元素上的 {@code page} 与之对应。
 */
@Data
public class ParseUnitResult {

    /** 单元序号（从 1 起：页号 / slide 号 / sheet 号；段落流格式为流序号） */
    private int unitIndex;

    /** 单元类型 */
    private UnitType type;

    /** 单元内元素（按产出顺序） */
    private List<ParseElement> elements = new ArrayList<>();

    /** 单元内行摘要（跨页运行文本/页码判定输入；文档级判定只读本字段） */
    private List<UnitLine> lines = new ArrayList<>();

    /** 单元级指标（信号判定的输入） */
    private PageMetric metric;

    /** 单元尺寸（物理页/幻灯片；无物理尺寸的格式为空；视口坐标口径） */
    private PageDimension dimension;

    /** 行摘要所在版面坐标系的高度（pt；页带判定用，无行为空） */
    private Double layoutHeight;
}
