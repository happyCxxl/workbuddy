package com.workbuddy.worker.parser.pdf;

import com.workbuddy.common.domain.input.FileReference;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.worker.parser.ParseContext;
import com.workbuddy.worker.parser.ParseProperties;
import com.workbuddy.worker.parser.pdf.layout.ProjectionPageLayoutAnalyzer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPageContentStream;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import com.workbuddy.worker.testkit.PropertiesFixtures;

/**
 * PDF 解析测试共享夹具：被测解析器、解析上下文与文档保存/画线等基础操作。
 */
final class PdfParserTestFixture {

    private final PdfBoxDocumentParser parser = new PdfBoxDocumentParser(new ProjectionPageLayoutAnalyzer());

    /**
     * 构造解析上下文。
     *
     * @param data PDF 字节
     * @return 解析上下文
     */
    ParseContext context(byte[] data) {
        ParseContext context = new ParseContext();
        FileReference fileRef = new FileReference();
        fileRef.setFileId("F-1");
        fileRef.setFileName("t.pdf");
        fileRef.setMimeType("application/pdf");
        context.setFileRef(fileRef);
        context.setInputStream(new ByteArrayInputStream(data));
        context.setProperties(PropertiesFixtures.parse());
        return context;
    }

    /**
     * 解析 PDF 字节。
     *
     * @param data PDF 字节
     * @return 解析结果
     */
    ParseSource parse(byte[] data) {
        return parser.parse(context(data));
    }

    /**
     * 按给定上下文解析。
     *
     * @param context 解析上下文
     * @return 解析结果
     */
    ParseSource parse(ParseContext context) {
        return parser.parse(context);
    }

    /**
     * 保存文档为字节。
     *
     * @param doc PDF 文档
     * @return 文档字节
     * @throws IOException 保存失败
     */
    static byte[] save(PDDocument doc) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.save(out);
        return out.toByteArray();
    }

    /**
     * 画一条线段。
     *
     * @param cs 内容流
     * @param x1 起点 x
     * @param y1 起点 y
     * @param x2 终点 x
     * @param y2 终点 y
     * @throws IOException 写入失败
     */
    static void drawLine(PDPageContentStream cs, float x1, float y1, float x2, float y2) throws IOException {
        cs.moveTo(x1, y1);
        cs.lineTo(x2, y2);
        cs.stroke();
    }

    /**
     * 8×8 测试图片。
     *
     * @return 图片
     */
    static BufferedImage sampleImage() {
        return new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
    }
}
