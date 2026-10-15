package com.workbuddy.worker.parser.pdf.layout;

import com.workbuddy.worker.parser.ParseProperties;

import java.util.List;

/**
 * 分栏与阅读顺序能力（PDF 解析器专用）：按页面几何事实找栏沟、定阅读顺序。
 *
 * <p>输入 {@link Input}（页面尺寸 + 盒子列表：字符级用于找栏沟、行级用于排顺序），
 * 输出栏沟中心 x（{@link #gutters}）与阅读顺序 + 每行栏号（{@link #order}）。
 * 规则实现见 {@link ProjectionPageLayoutAnalyzer}；接入版面模型或外部服务时另加实现，解析器不改。
 *
 * <p>切行口径 {@link #crossesAny} / {@link #columnOf} 由解析器与实现共用，保证两边判栏一致。
 *
 * @author cxxl
 */
public interface PageLayoutAnalyzer {

    /**
     * 找栏沟：按盒子横向覆盖找宽度达标的空白带，并校验每栏占比与字符数。
     *
     * @param chars   字符级几何事实
     * @param options 判定阈值
     * @return 栏沟中心 x（按 x 升序；空表示单栏）
     */
    List<Double> gutters(Input chars, Options options);

    /**
     * 定阅读顺序：跨栏行把页面切成上下若干带，带内跨栏行在前、其余按栏序与栏内纵向顺序。
     *
     * @param lines   行级几何事实（已按栏切开；跨栏行横跨栏沟）
     * @param gutters 栏沟中心 x（空表示单栏，返回顺序即纵向顺序）
     * @return 阅读顺序与每行栏号
     */
    Result order(Input lines, List<Double> gutters);

    /** 横向范围是否横跨栏沟（沟中心落在 [x, x+width) 内） */
    static boolean crosses(double x, double width, double gutter) {
        return x < gutter && x + width > gutter;
    }

    /** 横向范围是否横跨任一栏沟 */
    static boolean crossesAny(double x, double width, List<Double> gutters) {
        return gutters.stream().anyMatch(gutter -> crosses(x, width, gutter));
    }

    /** 中心落在第几栏（横跨栏沟返回 -1） */
    static int columnOf(double x, double width, List<Double> gutters) {
        return crossesAny(x, width, gutters) ? -1 : columnOfCenter(x + width / 2, gutters);
    }

    /** 中心点在栏沟的哪一侧（只看中心与栏沟的先后，不管横向范围是否越沟） */
    static int columnOfCenter(double center, List<Double> gutters) {
        int column = 0;
        for (double gutter : gutters) {
            if (center > gutter) {
                column++;
            }
        }
        return column;
    }

    /**
     * 页面几何输入：字符级与行级共用同一形状。
     *
     * @param pageWidth  页宽（pt）
     * @param pageHeight 页高（pt）
     * @param boxes      盒子列表（字符盒或行盒，按页面原有顺序）
     */
    record Input(double pageWidth, double pageHeight, List<Box> boxes) {
    }

    /**
     * 盒子：左上角坐标 + 宽高（y 向下为正，与元素坐标系一致）。
     *
     * @param x      左边界
     * @param y      上边界
     * @param width  宽
     * @param height 高
     */
    record Box(double x, double y, double width, double height) {
    }

    /**
     * 阅读顺序结果。
     *
     * @param placed  按阅读顺序排列的行位置
     * @param gutters 判定出的栏沟中心 x
     */
    record Result(List<Placed> placed, List<Double> gutters) {

        /**
         * 行位置：输入下标 + 栏号。
         *
         * @param index  输入 boxes 的下标
         * @param column 栏号（0 起；-1 表示跨栏行）
         */
        public record Placed(int index, int column) {
        }    }

    /**
     * 判定阈值（由解析阈值映射，见 {@link #of}）。
     *
     * @param gutterMinWidth    栏沟最小宽度（pt）
     * @param gutterMaxCoverage 栏沟最大竖直覆盖比例
     * @param columnMinShare    每栏最小字符占比
     * @param columnMinChars    每栏最少字符数
     * @param pageMinChars      单页参与判定所需的最少字符数
     * @param maxColumns        最大栏数
     */
    record Options(double gutterMinWidth, double gutterMaxCoverage, double columnMinShare,
                   int columnMinChars, int pageMinChars, int maxColumns) {

        /** 解析阈值 → 版面阈值 */
        public static Options of(ParseProperties properties) {
            return new Options(properties.getColumnGutterMinWidth(),
                    properties.getColumnGutterMaxCoverage(), properties.getColumnMinShare(),
                    properties.getColumnMinChars(), properties.getColumnMinPageChars(),
                    properties.getColumnMaxCount());
        }
    }
}
