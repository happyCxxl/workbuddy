package com.workbuddy.worker.parser.pdf.extract;

import com.workbuddy.common.domain.parse.BBox;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.parser.pdf.model.CharInfo;
import com.workbuddy.worker.parser.pdf.model.PageLine;
import com.workbuddy.worker.parser.pdf.model.Token;
import com.workbuddy.worker.parser.support.TocLineFeature;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * PDF 字符事实与行构建：逐词回调收集字符坐标、按字间距分词、构建带 token 的 {@link PageLine}。
 */
public final class PdfTokenSupport {

    /** token 分词的空隙阈值（字符 x 间距 ÷ 字号） */
    private static final double TOKEN_GAP_RATIO = 0.35;

    private PdfTokenSupport() {
    }

    /**
     * 单页字符事实。
     *
     * @param chars             字符事实（按 PDFBox 产出顺序）
     * @param unmappedFontNames 缺 Unicode 映射的字体名
     */
    public record StrippedPage(List<CharInfo> chars, Set<String> unmappedFontNames) {
    }

    /**
     * 提取单页字符事实：一次逐词回调 = 一个词，每个码点带版面坐标与字体事实。
     *
     * @param doc    PDF 文档
     * @param pageNo 页码（从 1 开始）
     * @return 字符事实与缺 Unicode 映射的字体名
     * @throws IOException 读取页面内容失败
     */
    public static StrippedPage strip(PDDocument doc, int pageNo) throws IOException {
        PositionStripper stripper = new PositionStripper();
        stripper.setStartPage(pageNo);
        stripper.setEndPage(pageNo);
        stripper.getText(doc);
        return new StrippedPage(List.copyOf(stripper.chars), stripper.unmappedFontNames());
    }

    /**
     * 行内 token 分词：字符按 x 排序，间距超阈值即断（空白字符不进入 token，自然形成词间断点）。
     *
     * @param lineChars 行内字符
     * @return 行内 token（按 x 升序）
     */
    public static List<Token> tokens(List<CharInfo> lineChars) {
        List<CharInfo> sorted = lineChars.stream()
                .sorted(Comparator.comparingDouble(CharInfo::x))
                .toList();
        List<Token> tokens = new ArrayList<>();
        StringBuilder tokenText = new StringBuilder();
        double tokenStart = -1;
        double tokenEnd = 0;
        CharInfo prev = null;
        for (CharInfo c : sorted) {
            if (Character.isWhitespace(c.codePoint())) {
                continue;
            }
            if (NullUtil.isNull(prev)) {
                tokenStart = c.x();
            }
            double gap = NullUtil.isNull(prev) ? 0 : c.x() - (prev.x() + prev.width());
            if (NullUtil.isNotNull(prev) && gap > TOKEN_GAP_RATIO * Math.max(c.fontSize(), 1)) {
                tokens.add(new Token(tokenText.toString(), tokenStart, tokenEnd));
                tokenText = new StringBuilder();
                tokenStart = c.x();
            }
            tokenText.appendCodePoint(c.codePoint());
            tokenEnd = c.x() + c.width();
            prev = c;
        }
        if (!tokenText.isEmpty()) {
            tokens.add(new Token(tokenText.toString(), tokenStart, tokenEnd));
        }
        return tokens;
    }

    /**
     * 行构建：行内 x 排序 → token 分词 → 行文本重建 → 目录行特征判定。
     *
     * @param pageNo        页码
     * @param lineChars     行内字符（会被按 x 排序）
     * @param lineTextOptions 行文本重建阈值
     * @return 页行事实
     */
    public static PageLine buildLine(int pageNo, List<CharInfo> lineChars, LineTexts.Options lineTextOptions) {
        lineChars.sort(Comparator.comparingDouble(CharInfo::x));
        double x = lineChars.getFirst().x();
        BBox bounds = PdfGeometry.boundsOf(lineChars, CharInfo::x, CharInfo::y, CharInfo::width, CharInfo::height);
        double fontSize = lineChars.stream().mapToDouble(CharInfo::fontSize).average().orElse(0);
        CharInfo first = lineChars.getFirst();
        List<Token> tokens = tokens(lineChars);
        String text = LineTexts.lineText(lineChars, lineTextOptions);
        boolean tocCandidate = TocLineFeature.isTocLine(text);
        return new PageLine(pageNo, x, bounds.getY(), bounds.getWidth(), bounds.getHeight(), text, fontSize,
                first.fontName(), first.bold(), tokens, List.copyOf(lineChars), tocCandidate);
    }

    /** 逐词回调收集字符事实（一次回调 = 一个词，首末码点标记词边界） */
    private static final class PositionStripper extends PDFTextStripper {

        private final List<CharInfo> chars = new ArrayList<>();

        /** 字体名 → 是否有 Unicode 映射（逐字符回调查一次即缓存） */
        private final Map<String, Boolean> unicodeMapped = new LinkedHashMap<>();

        private PositionStripper() {
            super();
        }

        /**
         * 文本片段回调：把每个字形连同其版面坐标收进字符事实。
         *
         * @param text          片段文本
         * @param textPositions 字形位置
         */
        @Override
        protected void writeString(String text, List<TextPosition> textPositions) {
            List<TextPosition> positions = textPositions.stream()
                    .filter(tp -> NullUtil.isNotNull(tp.getUnicode()))
                    .toList();
            for (TextPosition tp : positions) {
                String unicode = tp.getUnicode();
                boolean bold = tp.getFont().getName().toLowerCase(Locale.ROOT).contains("bold");
                unicodeMapped.computeIfAbsent(tp.getFont().getName(),
                        key -> !PdfFontMapping.lacksUnicodeMapping(tp.getFont()));
                for (int i = 0; i < unicode.length(); ) {
                    int cp = unicode.codePointAt(i);
                    chars.add(new CharInfo(tp.getXDirAdj(), tp.getYDirAdj(), tp.getWidthDirAdj(),
                            tp.getHeightDir(), tp.getFontSizeInPt(), tp.getFont().getName(), bold, cp));
                    i += Character.charCount(cp);
                }
            }
        }

        /** 缺 Unicode 映射的字体名集合（页级指标统计用） */
        private Set<String> unmappedFontNames() {
            Set<String> names = new HashSet<>();
            unicodeMapped.forEach((name, mapped) -> {
                if (!mapped) {
                    names.add(name);
                }
            });
            return names;
        }
    }
}
