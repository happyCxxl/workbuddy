package com.workbuddy.worker.parser.pdf.extract;

import com.workbuddy.common.domain.parse.BBox;

import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * PDF 解析共用几何口径：盒式元素的包围盒与页底截断判定。
 */
public final class PdfGeometry {

    /** 页底截断判定：行底边进入页面底部比例 */
    private static final double PAGE_BOTTOM_RATIO = 0.95;

    private PdfGeometry() {
    }

    /**
     * 一组盒式元素的包围盒：左/上取最小、右/下取最大（行与字符共用同一实现）。
     *
     * @param items  元素列表
     * @param x      横坐标读取器
     * @param y      纵坐标读取器
     * @param width  宽度读取器
     * @param height 高度读取器
     * @param <T>    元素类型
     * @return 包围盒；列表为空时各边为 0
     */
    public static <T> BBox boundsOf(List<T> items, ToDoubleFunction<T> x, ToDoubleFunction<T> y,
                             ToDoubleFunction<T> width, ToDoubleFunction<T> height) {
        double left = items.stream().mapToDouble(x).min().orElse(0);
        double top = items.stream().mapToDouble(y).min().orElse(0);
        double right = items.stream()
                .mapToDouble(item -> x.applyAsDouble(item) + width.applyAsDouble(item)).max().orElse(0);
        double bottom = items.stream()
                .mapToDouble(item -> y.applyAsDouble(item) + height.applyAsDouble(item)).max().orElse(0);
        return new BBox(left, top, right - left, bottom - top);
    }

    /**
     * 表格是否贴到页底：包围盒底边进入页面底部比例。
     *
     * @param bounds     包围盒
     * @param pageHeight 页高
     * @return 贴底为 true
     */
    public static boolean cutAtPageBottom(BBox bounds, double pageHeight) {
        return bounds.getY() + bounds.getHeight() >= pageHeight * PAGE_BOTTOM_RATIO;
    }
}
