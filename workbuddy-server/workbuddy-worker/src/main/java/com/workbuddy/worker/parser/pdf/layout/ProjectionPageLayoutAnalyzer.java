package com.workbuddy.worker.parser.pdf.layout;

import com.workbuddy.common.utils.NullUtil;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;

/**
 * 投影法分栏实现：字符横向覆盖做成直方图，其中竖直覆盖比例低于门槛、且宽度达标的横向带算栏沟；
 * 跨栏行（横跨栏沟的行）把页面切成上下若干带，带内跨栏行在前、其余按栏序与栏内纵向顺序。
 * 单栏页（无栏沟）返回纵向顺序，与不启用分栏时一致。
 */
@Component
public class ProjectionPageLayoutAnalyzer implements PageLayoutAnalyzer {

    /** 覆盖直方图分箱宽度（pt） */
    private static final double BIN_WIDTH = 1.0;

    /**
     * 栏间空白（gutter）检测：按字符投影的空白带找出分栏位置。
     *
     * @param chars   行内字符
     * @param options 版面分析阈值
     * @return 分栏边界（升序横向坐标）
     */
    @Override
    public List<Double> gutters(Input chars, Options options) {
        List<Box> boxes = chars.boxes().stream()
                .filter(box -> box.width() > 0 && box.height() > 0)
                .toList();
        if (boxes.size() < options.pageMinChars()) {
            return List.of();
        }
        double left = boxes.stream().mapToDouble(Box::x).min().orElse(0);
        double right = boxes.stream().mapToDouble(box -> box.x() + box.width()).max().orElse(0);
        double top = boxes.stream().mapToDouble(Box::y).min().orElse(0);
        double bottom = boxes.stream().mapToDouble(box -> box.y() + box.height()).max().orElse(0);
        int binCount = (int) Math.ceil((right - left) / BIN_WIDTH);
        int rowCount = (int) Math.ceil((bottom - top) / BIN_WIDTH);
        if (binCount < 2 || rowCount < 1) {
            return List.of();
        }
        BitSet[] coveredRows = new BitSet[binCount];
        for (Box box : boxes) {
            int from = Math.max((int) Math.floor((box.x() - left) / BIN_WIDTH), 0);
            int to = Math.min((int) Math.ceil((box.x() + box.width() - left) / BIN_WIDTH), binCount);
            int rowFrom = Math.max((int) Math.floor((box.y() - top) / BIN_WIDTH), 0);
            int rowTo = Math.min((int) Math.ceil((box.y() + box.height() - top) / BIN_WIDTH), rowCount);
            for (int i = from; i < to; i++) {
                if (NullUtil.isNull(coveredRows[i])) {
                    coveredRows[i] = new BitSet(rowCount);
                }
                coveredRows[i].set(rowFrom, rowTo);
            }
        }
        List<double[]> runs = lowCoverageRuns(coveredRows, rowCount, left, options);
        if (runs.isEmpty()) {
            return List.of();
        }
        // 候选沟按宽度从大到小逐个试：加进去不会产生过细的栏才接受（行尾参差会形成窄假沟）
        List<double[]> candidates = runs.stream()
                .sorted(Comparator.comparingDouble((double[] run) -> run[1]).reversed())
                .toList();
        List<Double> accepted = new ArrayList<>();
        for (double[] candidate : candidates) {
            if (accepted.size() >= Math.max(options.maxColumns() - 1, 1)) {
                break;
            }
            List<Double> trial = new ArrayList<>(accepted);
            trial.add(candidate[0] + candidate[1] / 2);
            trial.sort(Double::compareTo);
            if (columnsAcceptable(boxes, trial, options)) {
                accepted = trial;
            }
        }
        return accepted;
    }

    /**
     * 阅读顺序：按分栏边界把行归栏后重排。
     *
     * @param lines   行
     * @param gutters 分栏边界
     * @return 阅读顺序结果
     */
    @Override
    public Result order(Input lines, List<Double> gutters) {
        List<Box> boxes = lines.boxes();
        List<Integer> byY = new ArrayList<>();
        for (int i = 0; i < boxes.size(); i++) {
            byY.add(i);
        }
        byY.sort(Comparator.comparingDouble(index -> boxes.get(index).y()));
        List<Result.Placed> placed = new ArrayList<>();
        List<Integer> band = new ArrayList<>();
        for (int index : byY) {
            if (PageLayoutAnalyzer.crossesAny(boxes.get(index).x(), boxes.get(index).width(), gutters)) {
                flushBand(placed, band, boxes, gutters);
                band = new ArrayList<>();
            }
            band.add(index);
        }
        flushBand(placed, band, boxes, gutters);
        return new Result(placed, gutters);
    }

    /** 结算一带：跨栏行按纵向顺序在前，其余按栏序、栏内纵向顺序 */
    private static void flushBand(List<Result.Placed> placed, List<Integer> band,
                                  List<Box> boxes, List<Double> gutters) {
        band.stream()
                .filter(index -> PageLayoutAnalyzer.crossesAny(boxes.get(index).x(), boxes.get(index).width(), gutters))
                .sorted(Comparator.comparingDouble(index -> boxes.get(index).y()))
                .forEach(index -> placed.add(new Result.Placed(index, -1)));
        band.stream()
                .filter(index -> !PageLayoutAnalyzer.crossesAny(boxes.get(index).x(),
                        boxes.get(index).width(), gutters))
                .sorted(Comparator
                        .comparingInt((Integer index) -> PageLayoutAnalyzer.columnOf(boxes.get(index).x(),
                                boxes.get(index).width(), gutters))
                        .thenComparingDouble(index -> boxes.get(index).y()))
                .forEach(index -> placed.add(new Result.Placed(
                        index, PageLayoutAnalyzer.columnOf(boxes.get(index).x(), boxes.get(index).width(), gutters))));
    }

    /** 竖直覆盖比例低于门槛的连续横向箱成段，段宽达门槛即候选栏沟（返回 {起点 x, 宽度}） */
    private static List<double[]> lowCoverageRuns(BitSet[] coveredRows, int rowCount, double left,
                                                  Options options) {
        List<double[]> runs = new ArrayList<>();
        int start = -1;
        for (int i = 0; i <= coveredRows.length; i++) {
            boolean low = i < coveredRows.length
                    && coverage(coveredRows[i], rowCount) <= options.gutterMaxCoverage();
            if (low && start < 0) {
                start = i;
            } else if (!low && start >= 0) {
                double width = (i - start) * BIN_WIDTH;
                if (width >= options.gutterMinWidth()) {
                    runs.add(new double[] {left + start * BIN_WIDTH, width});
                }
                start = -1;
            }
        }
        return runs;
    }

    /** 该横向箱被覆盖的纵向范围占比 */
    private static double coverage(BitSet covered, int rowCount) {
        return NullUtil.isNull(covered) || rowCount == 0 ? 0 : (double) covered.cardinality() / rowCount;
    }

    /** 每栏字符数与占比达门槛才算成立 */
    private static boolean columnsAcceptable(List<Box> boxes, List<Double> gutters, Options options) {
        int[] counts = new int[gutters.size() + 1];
        for (Box box : boxes) {
            int column = PageLayoutAnalyzer.columnOf(box.x(), box.width(), gutters);
            if (column >= 0) {
                counts[column]++;
            }
        }
        for (int count : counts) {
            if (count < options.columnMinChars()
                    || (double) count / boxes.size() < options.columnMinShare()) {
                return false;
            }
        }
        return true;
    }
}
