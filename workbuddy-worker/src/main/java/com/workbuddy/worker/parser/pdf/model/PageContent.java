package com.workbuddy.worker.parser.pdf.model;

import com.workbuddy.common.domain.parse.signal.PageMetric;

import java.util.List;

/**
 * PDF 单页提取内容：页号/版面尺寸/页几何/页级指标/聚合行列表/页面线条/图片矩形。
 * 坐标一律是**版面坐标**（裁剪框左上原点）；产出元素时按 {@code geometry} 换算到视口坐标。
 *
 * @author cxxl
 */
public record PageContent(int pageNo, double pageWidth, double pageHeight, PdfPageGeometry geometry,
                          PageMetric metric, List<PageLine> lines, List<Segment> segments,
                          List<Region> imageBoxes) {
}
