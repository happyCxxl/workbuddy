package com.workbuddy.worker.parser.pdf.model;

import com.workbuddy.common.domain.parse.signal.PageMetric;

import java.util.List;

/**
 * PDF 单页提取内容：页号/版面尺寸/页几何/页级指标/聚合行列表/页面线条/图片矩形。
 * 坐标一律是**版面坐标**（裁剪框左上原点）；产出元素时按 {@code geometry} 换算到视口坐标。
 *
 * @param pageNo     页号
 * @param pageWidth  版面宽度
 * @param pageHeight 版面高度
 * @param geometry   页几何（旋转与坐标换算）
 * @param metric     页级指标
 * @param lines      聚合行
 * @param segments   页面横竖线段
 * @param imageBoxes 图片占位矩形
 */
public record PageContent(int pageNo, double pageWidth, double pageHeight, PdfPageGeometry geometry,
                          PageMetric metric, List<PageLine> lines, List<Segment> segments,
                          List<Region> imageBoxes) {
}
