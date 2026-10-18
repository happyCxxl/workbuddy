package com.workbuddy.worker.parser.pdf.model;

/**
 * PDF 行内 token（词）：文本 + x 起止坐标（表格列对齐/文字占比口径共用）。
 */
public record Token(String text, double xStart, double xEnd) {
}
