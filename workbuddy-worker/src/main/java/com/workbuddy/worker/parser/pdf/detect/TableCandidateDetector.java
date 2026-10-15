package com.workbuddy.worker.parser.pdf.detect;


import com.workbuddy.worker.parser.pdf.model.PageLine;
import com.workbuddy.worker.parser.pdf.model.Token;

import java.util.ArrayList;
import java.util.List;

/**
 * PDF 表格候选检测（PdfBoxDocumentParser 专用助手）：
 * 行级"宽列距"启发式判候选 → 列区间划分 → 覆盖度校验。
 * 列边界同时取 token 左起点与右边界（数字列右对齐时左起点逐行不同、右边界一致），
 * 容差按字号折算、由入参给出，不写固定像素值。
 *
 * @author cxxl
 */
public final class TableCandidateDetector {

    /** 表格候选：单行 token 数下限 */
    public static final int MIN_TOKENS = 2;

    /** 覆盖度校验：覆盖行占比下限 */
    private static final double COVER_RATIO = 0.6;

    private TableCandidateDetector() {
    }

    /** 表格候选：多 token 且存在"宽列距"（普通正文单词间距窄，表格列距宽） */
    public static boolean isCandidate(PageLine line) {
        if (line.tokens().size() < MIN_TOKENS) {
            return false;
        }
        double wideGap = Math.max(2.0 * Math.max(line.fontSize(), 1), 6.0);
        for (int i = 1; i < line.tokens().size(); i++) {
            double gap = line.tokens().get(i).xStart() - line.tokens().get(i - 1).xEnd();
            if (gap > wideGap) {
                return true;
            }
        }
        return false;
    }

    /** 列区间：版面坐标下的左右边界（保留的列首尾相接，覆盖整块宽度） */
    public record Column(double left, double right) {
    }

    /** 对齐线聚类：一组 x 值按容差合并为升序簇（左起点与右边界共用） */
    public static List<Double> clusterEdges(List<Double> values, double tolerance) {
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compare);
        List<Double> clusters = new ArrayList<>();
        double current = -1;
        for (double value : sorted) {
            if (current < 0 || value - current > tolerance) {
                clusters.add(value);
                current = value;
            } else {
                current = (current + value) / 2;
            }
        }
        return clusters;
    }

    /**
     * 列划分：token 左起点与右边界各自聚成对齐线，相邻对齐线之间的区间作候选列；
     * 列内 token 按**中心点**归属（右对齐数字列的中心点稳定），再按行支持度过滤，
     * 最后把保留的列首尾相接 —— 单元格 bbox 覆盖整块宽度且列间不重叠。
     *
     * @param tokenMatrix    块内各行 token
     * @param minSupportRows 列被保留所需的最少行数
     * @param tolerance      边界合并容差（按字号折算后传入）
     */
    public static List<Column> columns(List<List<Token>> tokenMatrix, int minSupportRows, double tolerance) {
        List<Double> edges = new ArrayList<>();
        for (List<Token> line : tokenMatrix) {
            for (Token token : line) {
                edges.add(token.xStart());
                edges.add(token.xEnd());
            }
        }
        List<Double> boundaries = clusterEdges(edges, tolerance);
        List<Column> kept = new ArrayList<>();
        for (int i = 0; i + 1 < boundaries.size(); i++) {
            Column column = new Column(boundaries.get(i), boundaries.get(i + 1));
            if (supportRows(tokenMatrix, column) >= minSupportRows) {
                kept.add(column);
            }
        }
        return join(kept, tokenMatrix.stream().flatMap(List::stream).mapToDouble(Token::xEnd).max().orElse(0));
    }

    /** token 是否落在列区间内（按中心点判定） */
    public static boolean contains(Column column, Token token) {
        double center = (token.xStart() + token.xEnd()) / 2;
        return center >= column.left() && center < column.right();
    }

    /**
     * 表格置信校验：token 全部落在列上的行数达到 2 行、且占比不低于 60%。
     * 某些格为空的稀疏行只要没有列外 token 即计入覆盖；单 token 行不算独立行证据。
     */
    public static boolean enoughLinesCoverColumns(List<List<Token>> tokenMatrix, List<Column> columns) {
        int covered = 0;
        for (List<Token> line : tokenMatrix) {
            boolean inside = line.size() >= MIN_TOKENS
                    && line.stream().allMatch(token -> columns.stream().anyMatch(column -> contains(column, token)));
            if (inside) {
                covered++;
            }
        }
        return covered >= 2 && covered >= tokenMatrix.size() * COVER_RATIO;
    }

    /** 列的行支持度：token 中心点落在列区间内的行数 */
    private static int supportRows(List<List<Token>> tokenMatrix, Column column) {        int support = 0;
        for (List<Token> line : tokenMatrix) {
            if (line.stream().anyMatch(token -> contains(column, token))) {
                support++;
            }
        }
        return support;
    }

    /** 列首尾相接：每列右边界取下一列左边界，最后一列延伸到块内最大右边界 */
    private static List<Column> join(List<Column> columns, double blockRight) {
        List<Column> joined = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            double right = i + 1 < columns.size() ? columns.get(i + 1).left()
                    : Math.max(blockRight, columns.get(i).right());
            joined.add(new Column(columns.get(i).left(), right));
        }
        return joined;
    }

    /**
     * 列内文本是否稀疏：每列取「token 跨度 ÷ 列宽」的中位数，半数以上列不超过上限即判为表格。
     * 正文分栏的每列被整行文字填满（比例接近 1），表格单元格相对列宽是稀疏的。
     */
    public static boolean sparseColumns(List<List<Token>> tokenMatrix, List<Column> columns, double maxFillRatio) {
        if (columns.isEmpty()) {
            return false;
        }
        long sparse = columns.stream().filter(column -> fillRatio(tokenMatrix, column) <= maxFillRatio).count();
        return sparse >= Math.ceil(columns.size() / 2.0);
    }

    /** 单列的文本填充比例：列内各行 token 跨度占列宽比例的中位数 */
    private static double fillRatio(List<List<Token>> tokenMatrix, Column column) {
        List<Double> ratios = new ArrayList<>();
        double width = column.right() - column.left();
        for (List<Token> line : tokenMatrix) {
            List<Token> inside = line.stream().filter(token -> contains(column, token)).toList();
            if (inside.isEmpty() || width <= 0) {
                continue;
            }
            double left = inside.stream().mapToDouble(Token::xStart).min().orElse(0);
            double right = inside.stream().mapToDouble(Token::xEnd).max().orElse(0);
            ratios.add((right - left) / width);
        }
        if (ratios.isEmpty()) {
            return 0;
        }
        ratios.sort(Double::compare);
        return ratios.get(ratios.size() / 2);
    }
}
