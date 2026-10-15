package com.workbuddy.common.domain.parse.signal;

import lombok.Data;

/**
 * 页级原始指标（PDF 解析器逐页统计；信号判定器的输入）。
 *
 * @author cxxl
 */
@Data
public class PageMetric {

    /** 页码（从 1 起） */
    private int page;

    /** 非空白字符数 */
    private int charCount;

    /** 乱码率（0~1：替换字符/私用区/未分配码点，或可打印乱码带码点 ÷ 非空白字符数） */
    private double garbledRatio;

    /** CID 映射失败率（0~1：来自「Type0 且缺 ToUnicode 映射」字体的字符 ÷ 非空白字符数；其余路径不填） */
    private double cidMappingRatio;

    /** 文本 bbox 面积 ÷ 页面面积（0~1） */
    private double textAreaRatio;

    /** 图片 bbox 面积 ÷ 页面面积（0~1；PDF 逐页统计，其余路径不填） */
    private double imageAreaRatio;

    /** 矢量图形覆盖率（0~1：线段包围盒面积之和 ÷ 页面面积，封顶 1；PDF 逐页统计，其余路径不填） */
    private double vectorAreaRatio;

    /** 内嵌图片数（Office 单元统计；PDF 的图片覆盖走 imageAreaRatio，两条口径互斥回填） */
    private int imageCount;
}
