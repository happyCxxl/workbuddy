package com.workbuddy.worker.parser.pdf.model;

/**
 * 行级轻量摘要（文本 + 盒子）：跨页页眉页脚识别只需要行的文本与几何，
 * 大文件分窗时全档只留这一层，字符与 token 的重数据只在窗口内保留。
 *
 * @param text   行文本
 * @param x      左边界（版面坐标）
 * @param y      上边界（版面坐标）
 * @param width  行宽
 * @param height 行高
 */
public record TextLine(String text, double x, double y, double width, double height) {

    /**
     * 由提取行取摘要。
     *
     * @param line 聚合行
     * @return 行摘要
     */
    public static TextLine of(PageLine line) {
        return new TextLine(line.text(), line.x(), line.y(), line.width(), line.height());
    }
}
