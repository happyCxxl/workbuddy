package com.workbuddy.worker.parser.pdf.model;

import java.util.List;

/**
 * PDF 聚合行：坐标/文本/字号/字体/token 列表/字符列表/目录行特征（页眉页脚识别与正文组装的公共行模型）。
 *
 * @author cxxl
 */
public record PageLine(int page, double x, double y, double width, double height, String text,
                       double fontSize, String fontName, boolean bold, List<Token> tokens,
                       List<CharInfo> chars, boolean tocCandidate) {
}
