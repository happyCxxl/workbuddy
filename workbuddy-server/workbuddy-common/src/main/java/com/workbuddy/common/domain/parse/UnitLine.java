package com.workbuddy.common.domain.parse;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单元内的行摘要（文本 + 盒子）：跨页运行文本识别、页码同址与内容空洞度判定的输入；
 * 只有文本与几何，不含字符级数据。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UnitLine {

    /** 行文本 */
    private String text;

    /** 行左边界（pt） */
    private double x;

    /** 行上边界（pt） */
    private double y;

    /** 行宽（pt） */
    private double width;

    /** 行高（pt） */
    private double height;
}
