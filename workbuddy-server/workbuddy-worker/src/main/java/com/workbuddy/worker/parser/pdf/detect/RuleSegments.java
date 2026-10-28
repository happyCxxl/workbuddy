package com.workbuddy.worker.parser.pdf.detect;

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
import java.util.List;

/**
 * 页面图元收集：把内容流路径收集为横竖线段、把图片收集为占位矩形，
 * 并给出图片/矢量覆盖率与线段连通分量（相交或共线重叠即同组）。
 * 坐标统一为左上原点，与字符 y 同口径。
 */
public final class RuleSegments {

    /** 坐标与线位判定容差（pt） */
    private static final double TOLERANCE = 2.0;

    private RuleSegments() {
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
        return new Graphics(RuleSegmentMerger.merge(collector.segments), collector.imageBoxes);
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
     * 线段连通分量：相交或共线重叠的线段归入同一组。
     *
     * @param segments 线段
     * @return 连通分量列表（按首个线段的输入顺序）
     */
    static List<List<Segment>> components(List<Segment> segments) {
        List<List<Segment>> components = new ArrayList<>();
        boolean[] visited = new boolean[segments.size()];
        for (int i = 0; i < segments.size(); i++) {
            if (visited[i]) {
                continue;
            }
            List<Segment> component = new ArrayList<>();
            collectComponent(segments, visited, i, component);
            components.add(component);
        }
        return components;
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
                if (!visited[i] && RuleGeometry.intersects(current, segments.get(i))) {
                    visited[i] = true;
                    pending.add(i);
                }
            }
        }
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
