package com.workbuddy.worker.parser.pdf.model;

/**
 * PDF 字符事实：版面坐标（裁剪框左上原点）+ 字体 + 码点。
 * 行文本重建、单元格归属与词边界判定共用本模型；词边界由相邻字符间隙算出，不依赖提取库的词回调。
 */
public record CharInfo(double x, double y, double width, double height, double fontSize,
                       String fontName, boolean bold, int codePoint) {
}
