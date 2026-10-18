package com.workbuddy.worker.parser.pdf.detect;

import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.parser.pdf.model.Grid;
import com.workbuddy.worker.parser.pdf.model.Region;
import com.workbuddy.worker.parser.pdf.model.Segment;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.util.Matrix;

import java.awt.geom.Point2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 页面线条收集与线网格：采集页面矢量线段（横线/竖线），按相交关系聚成线框区域，
 * 再由区域内线条求列边界、行边界与分隔线缺失（合并单元格）。
 * 坐标统一为左上原点，与字符 y 同口径。
 */
public final class RuleLines {

    /** 坐标与线位判定容差（pt） */
    private static final double TOLERANCE = 2.0;

    /** 参与网格的最短线段（pt）：滤掉装饰性短线 */
    private static final double MIN_SEGMENT_LENGTH = 3.0;

    /** 单页参与线网格的线段上限：超出即跳过线检测（矢量密集页退回文本表格路径） */
    private static final int MAX_RULE_SEGMENTS = 2000;

    private RuleLines() {
    }

    /**
     * 页面图元。
     *
     * @param segments   横竖线段（左上原点坐标）
     * @param imageBoxes 图片占位矩形（左上原点坐标）
     */
    public record Graphics(List<Segment> segments, List<Region> imageBoxes) {
    }

    /**
     * 采集页面图元；页面内容流异常时返回空结果（该页走文本表格路径）。
     *
     * @param page       页面
     * @param pageHeight 页面高度（坐标系换算用）
     * @return 页面图元
     */
    public static Graphics collect(PDPage page, double pageHeight) {
        Collector collector = new Collector(page, pageHeight);
        try {
            collector.processPage(page);
        } catch (IOException e) {
            return new Graphics(List.of(), List.of());
        }
        return new Graphics(collector.segments, collector.imageBoxes);
    }

    /**
     * 单张图片的覆盖面积（超出页面的部分截掉）。
     *
     * @param box        图片占位矩形
     * @param pageWidth  页面宽度
     * @param pageHeight 页面高度
     * @return 有效覆盖面积；无交集时为 0
     */
    public static double imageArea(Region box, double pageWidth, double pageHeight) {
        double width = Math.min(box.right(), pageWidth) - Math.max(box.left(), 0);
        double height = Math.min(box.bottom(), pageHeight) - Math.max(box.top(), 0);
        return width > 0 && height > 0 ? width * height : 0;
    }

    /**
     * 图片覆盖面积占比：各图片矩形面积之和除以页面面积。
     *
     * @param imageBoxes 图片占位矩形
     * @param pageWidth  页面宽度
     * @param pageHeight 页面高度
     * @return 覆盖率（上限 1）；无图片或页面尺寸非法时为 0
     */
    public static double imageAreaRatio(List<Region> imageBoxes, double pageWidth, double pageHeight) {
        if (imageBoxes.isEmpty() || pageWidth <= 0 || pageHeight <= 0) {
            return 0;
        }
        double area = 0;
        for (Region box : imageBoxes) {
            area += imageArea(box, pageWidth, pageHeight);
        }
        return Math.min(area / (pageWidth * pageHeight), 1);
    }

    /**
     * 矢量图形覆盖率：线段包围盒（所有线段的外接矩形）面积 ÷ 页面面积（上限 1）。
     * 图表、示意图这类纯矢量页靠它把「无文本无图片」的页与真空白页区分开：
     * 成框成轴的线段会撑出成片区域，而单条页脚分隔线（高度为 0）不算覆盖。
     *
     * @param segments   线段
     * @param pageWidth  页面宽度
     * @param pageHeight 页面高度
     * @return 覆盖率（上限 1）
     */
    public static double vectorAreaRatio(List<Segment> segments, double pageWidth, double pageHeight) {
        if (segments.isEmpty() || pageWidth <= 0 || pageHeight <= 0) {
            return 0;
        }
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (Segment segment : segments) {
            minX = Math.min(minX, Math.min(segment.x1(), segment.x2()));
            maxX = Math.max(maxX, Math.max(segment.x1(), segment.x2()));
            minY = Math.min(minY, Math.min(segment.y1(), segment.y2()));
            maxY = Math.max(maxY, Math.max(segment.y1(), segment.y2()));
        }
        return Math.min((maxX - minX) * (maxY - minY) / (pageWidth * pageHeight), 1);
    }

    /**
     * 线框区域：相交线条聚成组件，取外接矩形并保留能形成至少 2 列 2 行网格的组件。
     *
     * @param segments       线段
     * @param lineCoverRatio 线段覆盖率门槛
     * @return 线框区域，按上边界升序
     */
    public static List<Region> regions(List<Segment> segments, double lineCoverRatio) {
        List<Segment> usable = segments.stream()
                .filter(s -> s.length() >= MIN_SEGMENT_LENGTH)
                .toList();
        if (usable.size() > MAX_RULE_SEGMENTS) {
            return List.of();
        }
        List<Region> regions = new ArrayList<>();
        boolean[] visited = new boolean[usable.size()];
        for (int i = 0; i < usable.size(); i++) {
            if (visited[i]) {
                continue;
            }
            List<Segment> component = new ArrayList<>();
            collectComponent(usable, visited, i, component);
            Region region = bounds(component);
            Grid grid = fit(component, region, lineCoverRatio);
            if (NullUtil.isNotNull(grid)) {
                regions.add(region);
            }
        }
        regions.sort(Comparator.comparingDouble(Region::top));
        return regions;
    }

    /**
     * 区域内求线网格。
     *
     * @param segments       区域内的线段
     * @param region         区域
     * @param lineCoverRatio 线段覆盖率门槛
     * @return 网格；列或行不足 2 段时为 null
     */
    public static Grid fit(List<Segment> segments, Region region, double lineCoverRatio) {
        List<Segment> verticals = fullyInside(segments, region, true, lineCoverRatio);
        List<Segment> horizontals = fullyInside(segments, region, false, lineCoverRatio);
        List<Double> columns = cluster(verticals.stream().map(Segment::x1).toList());
        List<Double> rows = cluster(horizontals.stream().map(Segment::y1).toList());
        if (columns.size() < 3 || rows.size() < 3) {
            return null;
        }
        List<List<Boolean>> columnGaps = new ArrayList<>();
        for (int r = 0; r + 1 < rows.size(); r++) {
            List<Boolean> rowGap = new ArrayList<>();
            for (int c = 1; c + 1 < columns.size(); c++) {
                rowGap.add(!covered(verticals, true, columns.get(c), rows.get(r), rows.get(r + 1), lineCoverRatio));
            }
            columnGaps.add(rowGap);
        }
        List<List<Boolean>> rowGaps = new ArrayList<>();
        for (int r = 1; r + 1 < rows.size(); r++) {
            List<Boolean> gaps = new ArrayList<>();
            for (int c = 0; c + 1 < columns.size(); c++) {
                gaps.add(!covered(horizontals, false, rows.get(r), columns.get(c), columns.get(c + 1), lineCoverRatio));
            }
            rowGaps.add(gaps);
        }
        return new Grid(columns, rows, columnGaps, rowGaps);
    }

    /** 区域内的线：按方向筛出，并要求其长度覆盖区域对应边长的比例 */
    private static List<Segment> fullyInside(List<Segment> segments, Region region, boolean vertical,
                                             double lineCoverRatio) {
        List<Segment> result = new ArrayList<>();
        for (Segment segment : segments) {
            if (vertical != segment.vertical()) {
                continue;
            }
            if (vertical) {
                if (region.left() - TOLERANCE <= segment.x1() && segment.x1() <= region.right() + TOLERANCE
                        && segment.length() >= (region.bottom() - region.top()) * lineCoverRatio) {
                    result.add(segment);
                }
            } else if (region.top() - TOLERANCE <= segment.y1() && segment.y1() <= region.bottom() + TOLERANCE
                    && segment.length() >= (region.right() - region.left()) * lineCoverRatio) {
                result.add(segment);
            }
        }
        return result;
    }

    /** 在给定带内是否存在覆盖比例达标的线 */
    private static boolean covered(List<Segment> segments, boolean vertical, double at,
                                   double bandFrom, double bandTo, double lineCoverRatio) {
        double band = bandTo - bandFrom;
        for (Segment segment : segments) {
            if (vertical != segment.vertical()) {
                continue;
            }
            double position = vertical ? segment.x1() : segment.y1();
            if (Math.abs(position - at) > TOLERANCE) {
                continue;
            }
            double low = vertical ? Math.min(segment.y1(), segment.y2()) : Math.min(segment.x1(), segment.x2());
            double high = vertical ? Math.max(segment.y1(), segment.y2()) : Math.max(segment.x1(), segment.x2());
            double overlap = Math.min(high, bandTo) - Math.max(low, bandFrom);
            if (band <= 0 || overlap >= band * lineCoverRatio) {
                return true;
            }
        }
        return false;
    }

    /** 一维聚类：容差内合并，取均值作代表位 */
    private static List<Double> cluster(List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        List<Double> centers = new ArrayList<>();
        double sum = 0;
        int count = 0;
        for (double value : sorted) {
            if (count > 0 && value - sum / count > TOLERANCE) {
                centers.add(sum / count);
                sum = 0;
                count = 0;
            }
            sum += value;
            count++;
        }
        if (count > 0) {
            centers.add(sum / count);
        }
        return centers;
    }

    private static void collectComponent(List<Segment> segments, boolean[] visited, int start,
                                         List<Segment> component) {
        List<Integer> pending = new ArrayList<>();
        pending.add(start);
        visited[start] = true;
        while (!pending.isEmpty()) {
            int index = pending.removeLast();
            Segment current = segments.get(index);
            component.add(current);
            for (int i = 0; i < segments.size(); i++) {
                if (!visited[i] && intersects(current, segments.get(i))) {
                    visited[i] = true;
                    pending.add(i);
                }
            }
        }
    }

    /** 两线相交或共线重叠即视为同一线框组件 */
    private static boolean intersects(Segment a, Segment b) {
        if (a.horizontal() == b.horizontal()) {
            if (a.horizontal()) {
                return Math.abs(a.y1() - b.y1()) <= TOLERANCE
                        && overlap(a.x1(), a.x2(), b.x1(), b.x2()) > TOLERANCE;
            }
            return Math.abs(a.x1() - b.x1()) <= TOLERANCE
                    && overlap(a.y1(), a.y2(), b.y1(), b.y2()) > TOLERANCE;
        }
        Segment horizontal = a.horizontal() ? a : b;
        Segment vertical = a.horizontal() ? b : a;
        double horizontalX1 = Math.min(horizontal.x1(), horizontal.x2());
        double horizontalX2 = Math.max(horizontal.x1(), horizontal.x2());
        double verticalY1 = Math.min(vertical.y1(), vertical.y2());
        double verticalY2 = Math.max(vertical.y1(), vertical.y2());
        return vertical.x1() >= horizontalX1 - TOLERANCE && vertical.x1() <= horizontalX2 + TOLERANCE
                && horizontal.y1() >= verticalY1 - TOLERANCE && horizontal.y1() <= verticalY2 + TOLERANCE;
    }

    private static double overlap(double a1, double a2, double b1, double b2) {
        return Math.min(Math.max(a1, a2), Math.max(b1, b2)) - Math.max(Math.min(a1, a2), Math.min(b1, b2));
    }

    private static Region bounds(List<Segment> segments) {
        double left = segments.stream().mapToDouble(s -> Math.min(s.x1(), s.x2())).min().orElse(0);
        double right = segments.stream().mapToDouble(s -> Math.max(s.x1(), s.x2())).max().orElse(0);
        double top = segments.stream().mapToDouble(s -> Math.min(s.y1(), s.y2())).min().orElse(0);
        double bottom = segments.stream().mapToDouble(s -> Math.max(s.y1(), s.y2())).max().orElse(0);
        return new Region(left, top, right, bottom);
    }

    /** 图形流引擎：把路径操作收集为横竖线段、把图片收集为占位矩形 */
    private static final class Collector extends PDFGraphicsStreamEngine {

        private final List<Segment> segments = new ArrayList<>();
        private final List<Region> imageBoxes = new ArrayList<>();
        private final double pageHeight;
        private double startX;
        private double startY;
        private double currentX;
        private double currentY;

        private Collector(PDPage page, double pageHeight) {
            super(page);
            this.pageHeight = pageHeight;
        }

        /**
         * 矩形拆成四条边，作为线框候选。
         *
         * @param p0 角点 0
         * @param p1 角点 1
         * @param p2 角点 2
         * @param p3 角点 3
         */
        @Override
        public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
            add(p0, p1);
            add(p1, p2);
            add(p2, p3);
            add(p3, p0);
        }

        /**
         * 记录子路径起点与当前点。
         *
         * @param x 横坐标
         * @param y 纵坐标
         */
        @Override
        public void moveTo(float x, float y) {
            startX = x;
            startY = y;
            currentX = x;
            currentY = y;
        }

        /**
         * 当前点到目标点的连线；非横竖的斜线会被过滤掉。
         *
         * @param x 目标横坐标
         * @param y 目标纵坐标
         */
        @Override
        public void lineTo(float x, float y) {
            add(new Point2D.Double(currentX, currentY), new Point2D.Double(x, y));
            currentX = x;
            currentY = y;
        }

        /**
         * 曲线不参与线框判定，只把当前点推进到终点。
         *
         * @param x1 控制点 1 横坐标
         * @param y1 控制点 1 纵坐标
         * @param x2 控制点 2 横坐标
         * @param y2 控制点 2 纵坐标
         * @param x3 终点横坐标
         * @param y3 终点纵坐标
         */
        @Override
        public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
            currentX = x3;
            currentY = y3;
        }

        /** 闭合子路径：当前点到起点补一条边。 */
        @Override
        public void closePath() {
            add(new Point2D.Double(currentX, currentY), new Point2D.Double(startX, startY));
            currentX = startX;
            currentY = startY;
        }

        /**
         * 当前点。
         *
         * @return 当前点
         */
        @Override
        public Point2D getCurrentPoint() {
            return new Point2D.Double(currentX, currentY);
        }

        /** 路径结束：本实现只按路径操作收集线段，无需额外处理。 */
        @Override
        public void endPath() {
        }

        /** 描边路径：线段在路径操作阶段已收集，这里无需处理。 */
        @Override
        public void strokePath() {
        }

        /**
         * 填充路径：不产生线框，无需处理。
         *
         * @param windingRule 填充规则
         */
        @Override
        public void fillPath(int windingRule) {
        }

        /**
         * 填充并描边：线段已在路径操作阶段收集，无需处理。
         *
         * @param windingRule 填充规则
         */
        @Override
        public void fillAndStrokePath(int windingRule) {
        }

        /**
         * 裁剪路径：不影响线框判定，无需处理。
         *
         * @param windingRule 裁剪规则
         */
        @Override
        public void clip(int windingRule) {
        }

        /**
         * 图片：把占位矩形换算成左上原点坐标后收集。
         *
         * @param pdImage 页面上的图片
         */
        @Override
        public void drawImage(PDImage pdImage) {
            // 图片占位矩形 = 单位正方形经当前变换矩阵映射（转成左上原点）
            Matrix matrix = getGraphicsState().getCurrentTransformationMatrix();
            Point2D p0 = matrix.transformPoint(0, 0);
            Point2D p1 = matrix.transformPoint(1, 0);
            Point2D p2 = matrix.transformPoint(0, 1);
            Point2D p3 = matrix.transformPoint(1, 1);
            double minX = Math.min(Math.min(p0.getX(), p1.getX()), Math.min(p2.getX(), p3.getX()));
            double maxX = Math.max(Math.max(p0.getX(), p1.getX()), Math.max(p2.getX(), p3.getX()));
            double minY = Math.min(Math.min(p0.getY(), p1.getY()), Math.min(p2.getY(), p3.getY()));
            double maxY = Math.max(Math.max(p0.getY(), p1.getY()), Math.max(p2.getY(), p3.getY()));
            imageBoxes.add(new Region(minX, pageHeight - maxY, maxX, pageHeight - minY));
        }

        /**
         * 渐变填充：不产生线框，无需处理。
         *
         * @param shadingName 渐变资源名
         */
        @Override
        public void shadingFill(COSName shadingName) {
        }

        /** 记一段：转成左上原点；零长与斜线不入网格 */
        private void add(Point2D from, Point2D to) {
            double x1 = from.getX();
            double y1 = pageHeight - from.getY();
            double x2 = to.getX();
            double y2 = pageHeight - to.getY();
            if (Math.abs(x1 - x2) <= TOLERANCE && Math.abs(y1 - y2) <= TOLERANCE) {
                return;
            }
            if (Math.abs(x1 - x2) > TOLERANCE && Math.abs(y1 - y2) > TOLERANCE) {
                return;
            }
            segments.add(new Segment(x1, y1, x2, y2));
        }
    }
}
