package com.workbuddy.worker.parser.pdf.extract;

import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.parser.ParseProperties;
import com.workbuddy.worker.parser.pdf.model.CharInfo;
import com.workbuddy.worker.parser.pdf.model.PageLine;

import java.util.List;

/**
 * 行文本重建（无状态纯函数）：按 PDFBox 逐词回调给出的词边界补分隔符，
 * 内容流里的真实空白压缩为一个空格；两侧同为 CJK 时不补空格。
 * 字距加宽排版的行（字符间隙显著且成片）不按词边界补空格 —— PDFBox 在字距加宽时
 * 会把每个字符当作独立的词回调，照词边界补空格会把单词拆成单字符。
 */
public final class LineTexts {

    private LineTexts() {
    }

    /** 行文本重建阈值：词间间隙比例、显著间隙的下限比例与占比门槛。 */
    public record Options(double wordGapRatio, double spacingFloorRatio, double spacingGapShare) {

        /** 解析阈值映射：词间间隙比例、字符间隙下限比例、显著间隙占比门槛。 */
        public static Options of(ParseProperties properties) {
            return new Options(properties.getWordGapRatio(),
                    properties.getCharacterSpacingFloorRatio(),
                    properties.getCharacterSpacingGapShare());
        }
    }

    /** 行文本：按相邻字符间隙补词间空格，真实空白压成一个空格；字距加宽的行不按词边界补。 */
    public static String lineText(List<CharInfo> sortedByX, Options options) {
        boolean characterSpacing = isCharacterSpacing(sortedByX, options);
        StringBuilder text = new StringBuilder();
        CharInfo prev = null;
        for (CharInfo current : sortedByX) {
            if (Character.isWhitespace(current.codePoint())) {
                appendSpace(text);
            } else {
                if (!characterSpacing && isWordBoundary(prev, current, options)) {
                    appendSpace(text);
                }
                text.appendCodePoint(current.codePoint());
            }
            prev = current;
        }
        if (!text.isEmpty() && text.charAt(text.length() - 1) == ' ') {
            text.setLength(text.length() - 1);
        }
        return text.toString();
    }

    /**
     * 词边界判定：相邻字符间隙达到「字号 × 词间间隙比例」即算词间空格。
     * 全部由几何算出，不依赖提取库的词回调 —— 旋转页上回调粒度会失真（把单词拆成单字符）。
     */
    private static boolean isWordBoundary(CharInfo prev, CharInfo current, Options options) {
        if (NullUtil.isNull(prev) || (isCjk(prev.codePoint()) && isCjk(current.codePoint()))) {
            return false;
        }
        double gap = current.x() - (prev.x() + prev.width());
        return gap > options.wordGapRatio() * Math.max(current.fontSize(), 1);
    }

    /**
     * 字距加宽判定：相邻字符间隙达到「字号 × 下限比例」即算显著间隙，
     * 显著间隙占全部间隙的比例达到门槛即判为字距加宽排版（Tc）。
     * 词间距排版只会在词边界出现个别显著间隙，占比达不到门槛。
     */
    private static boolean isCharacterSpacing(List<CharInfo> sortedByX, Options options) {
        int total = 0;
        int wide = 0;
        CharInfo prev = null;
        for (CharInfo current : sortedByX) {
            if (NullUtil.isNotNull(prev)) {
                total++;
                double floor = options.spacingFloorRatio() * Math.max(current.fontSize(), 1);
                if (current.x() - (prev.x() + prev.width()) >= floor) {
                    wide++;
                }
            }
            prev = current;
        }
        return wide >= 2 && (double) wide / total >= options.spacingGapShare();
    }

    /** 行间分隔：任一侧取不到可见字符、或两侧同为 CJK 时不补空格，其余补一个空格。 */
    public static String lineSeparator(PageLine upper, PageLine lower) {
        return lineSeparator(upper.text(), lower.text());
    }

    /** 文本间分隔：口径同行间分隔（供格内多行拼接复用）。 */
    public static String lineSeparator(String upperText, String lowerText) {
        int upperCodePoint = lastVisibleCodePoint(upperText);
        int lowerCodePoint = firstVisibleCodePoint(lowerText);
        if (upperCodePoint < 0 || lowerCodePoint < 0
                || (isCjk(upperCodePoint) && isCjk(lowerCodePoint))) {
            return "";
        }
        return " ";
    }

    /** CJK 码位：Han、假名、谚文、CJK 标点与符号、全角形式。 */
    public static boolean isCjk(int codePoint) {
        return (codePoint >= 0x3400 && codePoint <= 0x4DBF)
                || (codePoint >= 0x4E00 && codePoint <= 0x9FFF)
                || (codePoint >= 0xF900 && codePoint <= 0xFAFF)
                || (codePoint >= 0x3040 && codePoint <= 0x30FF)
                || (codePoint >= 0xAC00 && codePoint <= 0xD7AF)
                || (codePoint >= 0x1100 && codePoint <= 0x11FF)
                || (codePoint >= 0x3000 && codePoint <= 0x303F)
                || (codePoint >= 0xFF00 && codePoint <= 0xFFEF);
    }

    /** 追加一个空格：行首与连续空白都不追加 */
    private static void appendSpace(StringBuilder text) {
        if (!text.isEmpty() && text.charAt(text.length() - 1) != ' ') {
            text.append(' ');
        }
    }

    /** 首个非空白码位；取不到返回 -1 */
    private static int firstVisibleCodePoint(String text) {
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            if (!Character.isWhitespace(codePoint)) {
                return codePoint;
            }
            i += Character.charCount(codePoint);
        }
        return -1;
    }

    /** 末个非空白码位；取不到返回 -1 */
    private static int lastVisibleCodePoint(String text) {
        int result = -1;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            if (!Character.isWhitespace(codePoint)) {
                result = codePoint;
            }
            i += Character.charCount(codePoint);
        }
        return result;
    }
}
