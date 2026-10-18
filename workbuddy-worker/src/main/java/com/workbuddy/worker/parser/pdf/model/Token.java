package com.workbuddy.worker.parser.pdf.model;

/**
 * PDF 行内 token（词）：文本 + x 起止坐标（表格列对齐/文字占比口径共用）。
 *
 * @param text   token 文本
 * @param xStart 起点横坐标
 * @param xEnd   终点横坐标
 */
public record Token(String text, double xStart, double xEnd) {
}
