package com.workbuddy.worker.parser.pdf.model;

import java.util.List;

/**
 * PDF 聚合行：坐标/文本/字号/字体/token 列表/字符列表/目录行特征（页眉页脚识别与正文组装的公共行模型）。
 *
 * @param page         页号
 * @param x            行左边界（版面坐标）
 * @param y            行上边界（版面坐标）
 * @param width        行宽
 * @param height       行高
 * @param text         行文本
 * @param fontSize     主字号
 * @param fontName     主字体名
 * @param bold         是否加粗
 * @param tokens       行内 token
 * @param chars        行内字符
 * @param tocCandidate 是否目录行候选
 */
public record PageLine(int page, double x, double y, double width, double height, String text,
                       double fontSize, String fontName, boolean bold, List<Token> tokens,
                       List<CharInfo> chars, boolean tocCandidate) {
}
