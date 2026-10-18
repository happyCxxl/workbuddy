package com.workbuddy.worker.parser.pdf.extract;

import com.workbuddy.common.domain.parse.BBox;
import com.workbuddy.common.domain.parse.signal.PageMetric;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.common.utils.TextUtil;
import com.workbuddy.worker.parser.ParseContext;
import com.workbuddy.worker.parser.ParseProperties;
import com.workbuddy.worker.parser.pdf.detect.RuleLines;
import com.workbuddy.worker.parser.pdf.detect.TableCandidateDetector;
import com.workbuddy.worker.parser.pdf.layout.PageLayoutAnalyzer;
import com.workbuddy.worker.parser.pdf.model.CharInfo;
import com.workbuddy.worker.parser.pdf.model.PageContent;
import com.workbuddy.worker.parser.pdf.model.PageLine;
import com.workbuddy.worker.parser.pdf.model.PdfPageGeometry;
import com.workbuddy.worker.parser.pdf.model.Token;
import com.workbuddy.worker.parser.support.TocLineFeature;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
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
 * 单页提取：字符 + 坐标 + 字体 → 页级指标（字符数/乱码率/文字占比/图片面积）→
 * 行聚合（y 容差）→ 按栏沟切行 → 阅读顺序重排 → token 分词，产出 {@link PageContent}。
 */
public class PdfPageExtractor {

    /** token 分词的空隙阈值（字符 x 间距 ÷ 字号） */
    private static final double TOKEN_GAP_RATIO = 0.35;

    /** 跨栏行判定：中心落在非主栏的字符占比下限（长行只是溢出栏宽，越栏字符很少） */
    private static final double SPANNING_COLUMN_MIN_SHARE = 0.3;

    private final PageLayoutAnalyzer layoutAnalyzer;

    public PdfPageExtractor(PageLayoutAnalyzer layoutAnalyzer) {
        this.layoutAnalyzer = layoutAnalyzer;
    }

    /** 占比（分母为 0 时返回 0） */
    private static double ratio(int count, int total) {
        return total > 0 ? (double) count / total : 0;
    }

    /** 提取一页：字符级指标 → 行聚合 → 栏切分 → 阅读顺序 → 文字占比。 */
    public PageContent extractPage(PDDocument doc, int pageNo, ParseContext context) throws IOException {
        PositionStripper stripper = new PositionStripper();
        stripper.setStartPage(pageNo);
        stripper.setEndPage(pageNo);
        stripper.getText(doc);

        PDPage page = doc.getPage(pageNo - 1);
        // 版面尺寸取裁剪框（未设裁剪框时 PDFBox 回落到媒体框）：与渲染出来的可见页一致，
        // 页眉页脚带比例、贴页底判定都按它算；产出元素时再按页几何换算到视口坐标
        PdfPageGeometry geometry = PdfPageGeometry.of(page);
        double pageWidth = geometry.textWidth();
        double pageHeight = geometry.textHeight();

        // 页级指标（字符统计先行；文字占比改在行聚合后按行级 bbox 计算，见下方）
        PageMetric metric = new PageMetric();
        metric.setPage(pageNo);
        Set<String> unmappedFonts = stripper.unmappedFontNames();
        int nonBlank = 0;
        int nonCommon = 0;
        int printableMojibake = 0;
        int unmapped = 0;
        for (CharInfo c : stripper.chars) {
            if (Character.isWhitespace(c.codePoint())) {
                continue;
            }
            nonBlank++;
            if (TextUtil.isNonCommonChar(c.codePoint())) {
                nonCommon++;
            }
            if (TextUtil.isPrintableMojibakeChar(c.codePoint())) {
                printableMojibake++;
            }
            if (unmappedFonts.contains(c.fontName())) {
                unmapped++;
            }
        }
        metric.setCharCount(nonBlank);
        metric.setCidMappingRatio(ratio(unmapped, nonBlank));
        // 乱码率：不可读码点占比；可打印乱码带达阈值时按两者较大取（字体映射错位页由此不再漏判）
        double unreadableRatio = ratio(nonCommon, nonBlank);
        double printableRatio = ratio(printableMojibake, nonBlank);
        metric.setGarbledRatio(printableRatio >= context.getProperties().getPrintableGarbledRateThreshold()
                ? Math.max(unreadableRatio, printableRatio) : unreadableRatio);
        RuleLines.Graphics graphics = RuleLines.collect(page, pageHeight);
        metric.setImageAreaRatio(RuleLines.imageAreaRatio(graphics.imageBoxes(), pageWidth, pageHeight));
        metric.setVectorAreaRatio(RuleLines.vectorAreaRatio(graphics.segments(), pageWidth, pageHeight));

        // 行聚合（y 容差聚类，同 y 内保持产出顺序 → 栏沟处按断口切行 → 行内按 x 排序 → token 分词）
        List<CharInfo> sorted = stripper.chars.stream()
                .sorted(Comparator.comparingDouble(CharInfo::y))
                .toList();
        List<Double> gutters = detectGutters(stripper.chars, pageWidth, pageHeight,
                PageLayoutAnalyzer.Options.of(context.getProperties()));
        LineTexts.Options lineTextOptions = LineTexts.Options.of(context.getProperties());
        List<List<CharInfo>> bands = groupBands(sorted, context.getProperties().getLineYTolerance());
        double alignmentTolerance = alignmentTolerance(bands, context.getProperties());
        boolean[] tableBands = tableBands(bands, context.getProperties(), alignmentTolerance);
        List<PageLine> rawLines = new ArrayList<>();
        for (int i = 0; i < bands.size(); i++) {
            if (tableBands[i]) {
                rawLines.add(buildLine(pageNo, bands.get(i), lineTextOptions));
            } else {
                rawLines.addAll(buildLines(pageNo, bands.get(i), gutters, lineTextOptions,
                        context.getProperties()));
            }
        }
        List<PageLine> lines = orderLines(rawLines, gutters, pageWidth, pageHeight);

        // 文字占比：行级 bbox 累计（行宽 × 行高，行高取 max(字符高, 字号×1.2)），
        // 字符级小盒子累计会低估占版面积、误伤稀疏页
        double textArea = 0;
        for (PageLine line : lines) {
            double lineHeight = Math.max(line.height(), line.fontSize() * 1.2);
            textArea += line.width() * lineHeight;
        }
        metric.setTextAreaRatio(pageWidth * pageHeight > 0 ? textArea / (pageWidth * pageHeight) : 0);

        return new PageContent(pageNo, pageWidth, pageHeight, geometry, metric, lines, graphics.segments(),
                graphics.imageBoxes());
    }

    /** 栏沟检测：字符级投影交给版面端口（返回空即单栏） */
    private List<Double> detectGutters(List<CharInfo> chars, double pageWidth, double pageHeight,
                                       PageLayoutAnalyzer.Options options) {
        List<PageLayoutAnalyzer.Box> boxes = chars.stream()
                .map(c -> new PageLayoutAnalyzer.Box(c.x(), c.y(), c.width(), c.height()))
                .toList();
        return layoutAnalyzer.gutters(new PageLayoutAnalyzer.Input(pageWidth, pageHeight, boxes), options);
    }

    /**
     * 按栏沟处的断口切行：同一 y 带里同排的多栏文本之间有断口（间隙达词间间隙口径）或字符区间回跳/重叠，
     * 断口又落在某条栏沟中心附近时在此切一刀；真正横跨栏沟的连续文本（跨栏行）整带出一行。
     */
    private List<PageLine> buildLines(int pageNo, List<CharInfo> lineChars, List<Double> gutters,
                                      LineTexts.Options lineTextOptions, ParseProperties properties) {
        if (gutters.isEmpty()) {
            return List.of(buildLine(pageNo, lineChars, lineTextOptions));
        }
        return splitAtGutterBreaks(lineChars, gutters, properties).stream()
                .map(group -> buildLine(pageNo, group, lineTextOptions))
                .toList();
    }

    /**
     * 在栏沟处的断口切分一条 y 带：沿字符产出顺序走，相邻字符之间出现断口（间隙达词间间隙口径）
     * 或起点回跳/重叠，且下一字符已落到另一栏时切一刀。返回各组字符（未切时为单组）。
     */
    private static List<List<CharInfo>> splitAtGutterBreaks(List<CharInfo> lineChars, List<Double> gutters,
                                                            ParseProperties properties) {
        List<List<CharInfo>> groups = new ArrayList<>();
        List<CharInfo> group = new ArrayList<>();
        Map<Integer, Integer> columns = new LinkedHashMap<>();
        double right = -1;
        double fontSize = 0;
        for (CharInfo c : lineChars) {
            if (!group.isEmpty()) {
                double breakAt = c.x() - right;
                boolean broken = breakAt >= properties.getWordGapRatio() * Math.max(fontSize, 1) || breakAt < 0;
                if (broken && dominantColumn(columns)
                        != PageLayoutAnalyzer.columnOfCenter(c.x() + c.width() / 2, gutters)) {
                    groups.add(group);
                    group = new ArrayList<>();
                    columns = new LinkedHashMap<>();
                    fontSize = 0;
                }
            }
            if (group.isEmpty()) {
                right = c.x();
            }
            group.add(c);
            columns.merge(PageLayoutAnalyzer.columnOfCenter(c.x() + c.width() / 2, gutters), 1, Integer::sum);
            right = Math.max(right, c.x() + c.width());
            fontSize = Math.max(fontSize, c.fontSize());
        }
        if (!group.isEmpty()) {
            groups.add(group);
        }
        return groups;
    }

    /** 组内字符中心最多的栏（组归属的栏） */
    private static int dominantColumn(Map<Integer, Integer> columns) {
        return columns.entrySet().stream()
                .max(Comparator.comparingInt(Map.Entry::getValue))
                .map(Map.Entry::getKey)
                .orElse(0);
    }

    /** 阅读顺序重排：跨栏行分带，带内按栏序与栏内纵向顺序（单栏页保持纵向顺序） */
    private List<PageLine> orderLines(List<PageLine> lines, List<Double> gutters,
                                      double pageWidth, double pageHeight) {
        if (gutters.isEmpty() || lines.size() < 2) {
            return lines;
        }
        List<PageLayoutAnalyzer.Box> boxes = lines.stream()
                .map(line -> orderBox(line, gutters))
                .toList();
        PageLayoutAnalyzer.Result result = layoutAnalyzer.order(
                new PageLayoutAnalyzer.Input(pageWidth, pageHeight, boxes), gutters);
        return result.placed().stream().map(placed -> lines.get(placed.index())).toList();
    }

    /**
     * 排顺序用的行盒：单栏行按所属栏裁剪横向范围（长行溢出栏宽不算跨栏行），
     * 真正的跨栏行保持原宽（由它切上下带）。
     */
    private static PageLayoutAnalyzer.Box orderBox(PageLine line, List<Double> gutters) {
        if (!PageLayoutAnalyzer.crossesAny(line.x(), line.width(), gutters)
                || spansColumns(line, gutters, SPANNING_COLUMN_MIN_SHARE)) {
            return new PageLayoutAnalyzer.Box(line.x(), line.y(), line.width(), line.height());
        }
        int column = PageLayoutAnalyzer.columnOfCenter(line.x() + line.width() / 2, gutters);
        double left = column == 0 ? line.x() : Math.max(line.x(), gutters.get(column - 1));
        double right = column == gutters.size() ? line.x() + line.width()
                : Math.min(line.x() + line.width(), gutters.get(column));
        return new PageLayoutAnalyzer.Box(left, line.y(), Math.max(right - left, 0), line.height());
    }

    /** 行是否真正跨栏：中心落在非主栏的字符占比达门槛（只是长行溢出栏宽的行不算） */
    private static boolean spansColumns(PageLine line, List<Double> gutters, double minShare) {
        List<CharInfo> chars = line.chars();
        if (chars.isEmpty()) {
            return false;
        }
        Map<Integer, Integer> countByColumn = new LinkedHashMap<>();
        for (CharInfo c : chars) {
            countByColumn.merge(
                    PageLayoutAnalyzer.columnOfCenter(c.x() + c.width() / 2, gutters), 1, Integer::sum);
        }
        int dominant = countByColumn.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        return (double) (chars.size() - dominant) / chars.size() >= minShare;
    }

    /** 行带：同一 y 容差内的字符（按栏切分前的一整行） */
    private static List<List<CharInfo>> groupBands(List<CharInfo> sorted, double yTolerance) {
        List<List<CharInfo>> bands = new ArrayList<>();
        List<CharInfo> band = new ArrayList<>();
        double bandY = -1;
        for (CharInfo c : sorted) {
            if (band.isEmpty()) {
                band.add(c);
                bandY = c.y();
            } else if (Math.abs(c.y() - bandY) <= yTolerance) {
                band.add(c);
                bandY = (bandY + c.y()) / 2;
            } else {
                bands.add(band);
                band = new ArrayList<>();
                band.add(c);
                bandY = c.y();
            }
        }
        if (!band.isEmpty()) {
            bands.add(band);
        }
        return bands;
    }

    /** 对齐容差：按全页最大字号折算（与列聚类同一口径） */
    private static double alignmentTolerance(List<List<CharInfo>> bands, ParseProperties properties) {
        double maxFontSize = bands.stream().flatMap(List::stream)
                .mapToDouble(CharInfo::fontSize).max().orElse(0);
        return properties.getTableColumnToleranceRatio() * Math.max(maxFontSize, 1);
    }

    /**
     * 全页对齐位置：所有行带 token 的左起点与右边界聚类，保留被足够行带支持的位置。
     * 数字列右对齐时左起点逐行不同、右边界一致，两侧都取才认得出这一列。
     */
    private static List<Double> alignedPositions(List<List<CharInfo>> bands, double tolerance, int minRows) {
        List<Position> positions = new ArrayList<>();
        for (int index = 0; index < bands.size(); index++) {
            for (Token token : tokens(bands.get(index))) {
                positions.add(new Position(token.xStart(), index));
                positions.add(new Position(token.xEnd(), index));
            }
        }
        positions.sort(Comparator.comparingDouble(Position::value));
        List<Double> aligned = new ArrayList<>();
        List<Position> cluster = new ArrayList<>();
        double center = 0;
        for (Position position : positions) {
            if (!cluster.isEmpty() && Math.abs(position.value() - center) > tolerance) {
                addAligned(aligned, cluster, minRows);
                cluster = new ArrayList<>();
            }
            center = cluster.isEmpty() ? position.value()
                    : (center * cluster.size() + position.value()) / (cluster.size() + 1);
            cluster.add(position);
        }
        addAligned(aligned, cluster, minRows);
        return aligned;
    }

    /** 位置簇收口：簇内来源行带数达到下限即算一个对齐位置 */
    private static void addAligned(List<Double> aligned, List<Position> cluster, int minRows) {
        if (cluster.stream().map(Position::band).distinct().count() >= minRows) {
            aligned.add(cluster.stream().mapToDouble(Position::value).average().orElse(0));
        }
    }

    /**
     * 表格行带标记：先找「锚行」（行内落在 ≥3 个对齐位置上的 token 数达标），
     * 再把**锚行之间**、token 全部落在锚行 x 范围内的行带并入 —— 单元格内换行的续行与
     * 空单元格的稀疏行由此保住整行，不再被栏沟撕成两行。
     * 只有 2 列对齐时与双栏正文无法区分（双栏正文必须保持栏内阅读顺序），故锚行下限取 3。
     */
    private static boolean[] tableBands(List<List<CharInfo>> bands, ParseProperties properties, double tolerance) {
        List<List<Token>> tokensPerBand = bands.stream().map(PdfPageExtractor::tokens).toList();
        boolean[] result = new boolean[bands.size()];
        List<Double> alignedPositions = alignedPositions(bands, tolerance, properties.getTableAlignmentMinRows());
        if (alignedPositions.isEmpty()) {
            return result;
        }
        List<Integer> anchors = new ArrayList<>();
        for (int i = 0; i < bands.size(); i++) {
            if (matchedPositions(tokensPerBand.get(i), alignedPositions, tolerance)
                    >= properties.getTableRowMinColumns()) {
                anchors.add(i);
            }
        }
        if (anchors.size() < 2) {
            return result;
        }
        // 假表门限：正文分栏的每列被整行文字填满，列内文本不稀疏时不认表格区域（保持栏内阅读顺序）
        List<List<Token>> anchorTokens = anchors.stream().map(tokensPerBand::get).toList();
        double fillTolerance = alignmentTolerance(bands, properties);
        List<TableCandidateDetector.Column> anchorColumns = TableCandidateDetector.columns(anchorTokens,
                properties.getTableAlignmentMinRows(), fillTolerance);
        if (!TableCandidateDetector.sparseColumns(anchorTokens, anchorColumns,
                properties.getTableColumnMaxFillRatio())) {
            return result;
        }
        // 表格区域：锚行区间 + 两侧紧邻的合格行带（行距不超过锚行间距）
        int first = anchors.getFirst();
        int last = anchors.getLast();
        double left = bandLeft(tokensPerBand, first, last);
        double right = bandRight(tokensPerBand, first, last);
        double spacing = anchorSpacing(bands, anchors);
        while (first > 0 && bandY(bands, first) - bandY(bands, first - 1) <= spacing
                && qualifies(tokensPerBand.get(first - 1), left, right)) {
            first--;
        }
        while (last + 1 < bands.size() && bandY(bands, last + 1) - bandY(bands, last) <= spacing
                && qualifies(tokensPerBand.get(last + 1), left, right)) {
            last++;
        }
        for (int i = first; i <= last; i++) {
            result[i] = anchors.contains(i) || qualifies(tokensPerBand.get(i), left, right);
        }
        return result;
    }

    /** 区域内合格行带：token 数达下限且全部落在区域 x 范围内 */
    private static boolean qualifies(List<Token> tokens, double left, double right) {
        return tokens.size() >= TableCandidateDetector.MIN_TOKENS
                && tokens.stream().allMatch(token -> token.xStart() >= left && token.xEnd() <= right);
    }

    private static double bandLeft(List<List<Token>> tokensPerBand, int first, int last) {
        return tokensPerBand.subList(first, last + 1).stream().flatMap(List::stream)
                .mapToDouble(Token::xStart).min().orElse(0);
    }

    private static double bandRight(List<List<Token>> tokensPerBand, int first, int last) {
        return tokensPerBand.subList(first, last + 1).stream().flatMap(List::stream)
                .mapToDouble(Token::xEnd).max().orElse(0);
    }

    /** 锚行间距：相邻锚行 y 差的平均值（区域扩展与续行归并的纵向尺度） */
    private static double anchorSpacing(List<List<CharInfo>> bands, List<Integer> anchors) {
        double sum = 0;
        for (int i = 1; i < anchors.size(); i++) {
            sum += bandY(bands, anchors.get(i)) - bandY(bands, anchors.get(i - 1));
        }
        return anchors.size() > 1 ? sum / (anchors.size() - 1) : 0;
    }

    private static double bandY(List<List<CharInfo>> bands, int index) {
        return bands.get(index).getFirst().y();
    }

    /** 行内落在全页对齐位置（左起点或右边界）上的 token 数 */
    private static long matchedPositions(List<Token> tokens, List<Double> alignedPositions, double tolerance) {
        return tokens.stream()
                .filter(token -> aligns(token.xStart(), alignedPositions, tolerance)
                        || aligns(token.xEnd(), alignedPositions, tolerance))
                .count();
    }

    private static boolean aligns(double value, List<Double> positions, double tolerance) {
        return positions.stream().anyMatch(position -> Math.abs(position - value) <= tolerance);
    }

    /** 对齐位置候选：token 左起点 / 右边界 + 来源行带序号（支持度按行带去重） */
    private record Position(double value, int band) {
    }

    /** 行内 token 分词：字符间距超阈值即断（空白字符不进入 token，自然形成词间断点） */
    private static List<Token> tokens(List<CharInfo> lineChars) {
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

    /** 行构建：行内 x 排序 → token 分词（字符间距超阈值断词）→ 目录行特征判定 */
    private PageLine buildLine(int pageNo, List<CharInfo> lineChars, LineTexts.Options lineTextOptions) {
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
