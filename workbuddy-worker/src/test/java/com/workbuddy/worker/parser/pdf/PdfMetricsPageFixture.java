package com.workbuddy.worker.parser.pdf;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * 页级指标测试文档夹具：整页图片、小图、多图与纯矢量页。
 */
final class PdfMetricsPageFixture {

    /**
     * 造一页整页图片、无文本的 PDF。
     *
     * @return PDF 字节
     * @throws IOException 写入失败
     */
    byte[] buildFullPageImagePdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(595, 842));
            doc.addPage(page);
            BufferedImage image = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
            PDImageXObject xobject = LosslessFactory.createFromImage(doc, image);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(xobject, 0, 0, 595, 842);
            }
            return PdfParserTestFixture.save(doc);
        }
    }

    /**
     * 造一页：正文 + 小图（小图不产图片元素）。
     *
     * @return PDF 字节
     * @throws IOException 写入失败
     */
    byte[] buildSmallImagePdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(595, 842));
            doc.addPage(page);
            PDImageXObject xobject = LosslessFactory.createFromImage(doc, PdfParserTestFixture.sampleImage());
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                cs.beginText();
                cs.newLineAtOffset(72, 700);
                cs.showText("body text for small image page");
                cs.endText();
                cs.drawImage(xobject, 72, 100, 30, 30);
            }
            return PdfParserTestFixture.save(doc);
        }
    }

    /**
     * 造一页：两张各占页面积约 18% 的图（都过图片元素门槛）。
     *
     * @return PDF 字节
     * @throws IOException 写入失败
     */
    byte[] buildTwoImagePdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(595, 842));
            doc.addPage(page);
            PDImageXObject xobject = LosslessFactory.createFromImage(doc, PdfParserTestFixture.sampleImage());
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(xobject, 40, 500, 300, 300);
                cs.drawImage(xobject, 40, 150, 300, 300);
            }
            return PdfParserTestFixture.save(doc);
        }
    }

    /**
     * 造一页：无文本，只画一个 400×300pt 的矢量框（图表轮廓）。
     *
     * @return PDF 字节
     * @throws IOException 写入失败
     */
    byte[] buildVectorChartPage() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(595, 842));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setLineWidth(0.8f);
                float left = 100;
                float right = 500;
                float top = 700;
                float bottom = 400;
                PdfParserTestFixture.drawLine(cs, left, top, right, top);
                PdfParserTestFixture.drawLine(cs, left, bottom, right, bottom);
                PdfParserTestFixture.drawLine(cs, left, bottom, left, top);
                PdfParserTestFixture.drawLine(cs, right, bottom, right, top);
                PdfParserTestFixture.drawLine(cs, left, 500, right, 500);
            }
            return PdfParserTestFixture.save(doc);
        }
    }
}
