package com.workbuddy.worker.parser.pdf.extract;

import com.workbuddy.worker.parser.ParseProperties;
import com.workbuddy.worker.parser.pdf.detect.TableCandidateDetector;
import com.workbuddy.worker.parser.pdf.model.CharInfo;
import com.workbuddy.worker.parser.pdf.model.Token;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * PDF 表格行带判定：y 容差聚类成行带、全页对齐位置聚类、锚行与表格区域识别，
 * 供页提取器决定某一行带按表格整行输出还是按栏沟切行。
 */
public final class PdfTableBandSupport {

    private PdfTableBandSupport() {
    }

    /**
     * 行带分组：同一 y 容差内的字符聚成一带（按栏切分前的一整行）。
     *
     * @param sorted     按 y 升序的字符
     * @param yTolerance y 容差
     * @return 行带（按 y 升序）
     */
    public static List<List<CharInfo>> groupBands(List<CharInfo> sorted, double yTolerance) {
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

    /**
     * 对齐容差：按全页最大字号折算（与列聚类同一口径）。
     *
     * @param bands      行带
     * @param properties 解析阈值配置
     * @return 对齐容差（pt）
     */
    public static double alignmentTolerance(List<List<CharInfo>> bands, ParseProperties properties) {
        double maxFontSize = bands.stream().flatMap(List::stream)
                .mapToDouble(CharInfo::fontSize).max().orElse(0);
        return properties.getTableColumnToleranceRatio() * Math.max(maxFontSize, 1);
    }

    /**
     * 表格行带标记：先找「锚行」（行内落在 ≥3 个对齐位置上的 token 数达标），
     * 再把锚行之间、token 全部落在锚行 x 范围内的行带并入 —— 单元格内换行的续行与
     * 空单元格的稀疏行由此保住整行，不再被栏沟撕成两行。
     * 只有 2 列对齐时与双栏正文无法区分（双栏正文必须保持栏内阅读顺序），故锚行下限取 3。
     *
     * @param bands      行带
     * @param properties 解析阈值配置
     * @param tolerance  对齐容差
     * @return 与行带等长的表格行带标记
     */
    public static boolean[] tableBands(List<List<CharInfo>> bands, ParseProperties properties, double tolerance) {
        List<List<Token>> tokensPerBand = bands.stream().map(PdfTokenSupport::tokens).toList();
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

    /**
     * 全页对齐位置：所有行带 token 的左起点与右边界聚类，保留被足够行带支持的位置。
     * 数字列右对齐时左起点逐行不同、右边界一致，两侧都取才认得出这一列。
     */
    private static List<Double> alignedPositions(List<List<CharInfo>> bands, double tolerance, int minRows) {
        List<Position> positions = new ArrayList<>();
        for (int index = 0; index < bands.size(); index++) {
            for (Token token : PdfTokenSupport.tokens(bands.get(index))) {
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
}
