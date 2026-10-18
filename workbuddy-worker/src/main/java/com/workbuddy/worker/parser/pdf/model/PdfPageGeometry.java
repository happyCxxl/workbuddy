package com.workbuddy.worker.parser.pdf.model;

import com.workbuddy.common.domain.parse.BBox;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

/**
 * PDF 页几何：版面尺寸（裁剪框）+ 页旋转 → 视口坐标口径。
 * 版面判定（行聚合、分栏、页眉页脚带、表格网格）在**版面坐标**里做（裁剪框左上原点），
 * 产出元素时用本类换算到**视口坐标** —— 与 pdf.js `getViewport({ scale: 1 })` 同一套坐标，
 * 前端预览按它画高亮框，两边必须一致。
 */
public record PdfPageGeometry(double textWidth, double textHeight, int rotation) {

    /** 规范化页旋转：非 90 倍数（规范允许但 pdf.js 直接报错）按 0 处理 */
    public PdfPageGeometry {
        int value = ((rotation % 360) + 360) % 360;
        rotation = value == 90 || value == 180 || value == 270 ? value : 0;
    }

    /** 从 PDFBox 页对象取几何：尺寸用裁剪框（未设裁剪框时 PDFBox 自动回落到媒体框）。 */
    public static PdfPageGeometry of(PDPage page) {
        PDRectangle box = page.getCropBox();
        return new PdfPageGeometry(box.getWidth(), box.getHeight(), page.getRotation());
    }

    /** 视口宽度：旋转 90 / 270 时与版面高度互换。 */
    public double viewportWidth() {
        return rotated() ? textHeight : textWidth;
    }

    /** 视口高度：旋转 90 / 270 时与版面宽度互换。 */
    public double viewportHeight() {
        return rotated() ? textWidth : textHeight;
    }

    /** 版面坐标（左上原点）矩形 → 视口坐标矩形。 */
    public BBox toViewport(BBox box) {
        return toViewport(box.getX(), box.getY(), box.getWidth(), box.getHeight());
    }

    /**
     * 版面坐标（左上原点）矩形 → 视口坐标矩形：按页旋转映射矩形角点。
     * 90 / 270 度时版面的一根轴换到视口的另一根轴上，宽高互换是映射本身要求的；
     * 四个分支的算式与 pdf.js `getViewport({ scale: 1 })` 实测逐点一致（见 PdfPageGeometryTest）。
     */
    @SuppressWarnings("SuspiciousNameCombination")
    public BBox toViewport(double x, double y, double width, double height) {
        double right = x + width;
        double bottom = y + height;
        return switch (rotation) {
            case 90 -> new BBox(textHeight - bottom, x, height, width);
            case 180 -> new BBox(textWidth - right, textHeight - bottom, width, height);
            case 270 -> new BBox(y, textWidth - right, height, width);
            default -> new BBox(x, y, width, height);
        };
    }

    private boolean rotated() {
        return rotation == 90 || rotation == 270;
    }
}
