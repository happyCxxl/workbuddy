package com.workbuddy.worker.parser.pdf.asset;

import com.workbuddy.common.domain.parse.BBox;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.parser.asset.ParseAssetException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * PDF 页图渲染：按 DPI 渲染整页并缓存当前页，按视口 bbox 裁剪图片区域。
 *
 * <p>只缓存最近一页，解析按页推进时不会把整档页图留在堆里。
 * <p>页间并发装配时，渲染与裁剪串行执行：PDFRenderer 与缓存槽都不是线程安全的，
 * 串行只锁住渲染本身，不阻塞模型调用。
 */
public final class PdfPageImageRenderer {

    private final PDFRenderer renderer;

    private final int dpi;

    private int cachedPageNo = -1;

    private BufferedImage cachedPage;

    /**
     * 构造页图渲染器。
     *
     * @param document PDF 文档
     * @param dpi      渲染 DPI
     */
    public PdfPageImageRenderer(PDDocument document, int dpi) {
        this.renderer = new PDFRenderer(document);
        this.dpi = Math.max(dpi, 1);
    }

    /**
     * 渲染整页（当前页复用缓存）。
     *
     * @param pageNo 页码（从 1 开始）
     * @return 页图
     */
    public synchronized BufferedImage pageImage(int pageNo) {
        if (pageNo == cachedPageNo) {
            return cachedPage;
        }
        try {
            cachedPage = renderer.renderImageWithDPI(pageNo - 1, dpi);
            cachedPageNo = pageNo;
            return cachedPage;
        } catch (IOException e) {
            throw new ParseAssetException("PDF 页图渲染失败: page=" + pageNo, e);
        }
    }

    /**
     * 按视口 bbox 裁剪页图；bbox 为空或越界时返回整页。
     *
     * @param pageImage       页图
     * @param bbox            视口坐标区域
     * @param viewportWidth   视口宽（pt）
     * @param viewportHeight  视口高（pt）
     * @return 裁剪图
     */
    public synchronized BufferedImage crop(BufferedImage pageImage, BBox bbox, double viewportWidth,
                                           double viewportHeight) {
        if (NullUtil.isNull(bbox) || viewportWidth <= 0 || viewportHeight <= 0) {
            return pageImage;
        }
        int left = clamp((int) Math.floor(bbox.getX() / viewportWidth * pageImage.getWidth()),
                0, pageImage.getWidth() - 1);
        int top = clamp((int) Math.floor(bbox.getY() / viewportHeight * pageImage.getHeight()),
                0, pageImage.getHeight() - 1);
        int width = clamp((int) Math.round(bbox.getWidth() / viewportWidth * pageImage.getWidth()),
                1, pageImage.getWidth() - left);
        int height = clamp((int) Math.round(bbox.getHeight() / viewportHeight * pageImage.getHeight()),
                1, pageImage.getHeight() - top);
        return pageImage.getSubimage(left, top, width, height);
    }

    /**
     * 图片编码为 PNG。
     *
     * @param image 图片
     * @return PNG 字节
     */
    public byte[] png(BufferedImage image) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new ParseAssetException("PDF 页图编码失败", e);
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
