package com.workbuddy.worker.parser.pdf;

import com.workbuddy.common.domain.input.FileReference;
import com.workbuddy.common.domain.parse.ParseAsset;
import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.enums.parse.ElementType;
import com.workbuddy.common.enums.parse.ParseAssetKind;
import com.workbuddy.worker.parser.ParseContext;
import com.workbuddy.worker.parser.pdf.layout.ProjectionPageLayoutAnalyzer;
import com.workbuddy.worker.testkit.PropertiesFixtures;
import com.workbuddy.worker.testkit.RecordingAssetSink;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PDF 图片资产：页图与裁剪图写入资产存储，元素 assetRef 指向真实资产。
 */
class PdfImageAssetTest {

    /** 整页图片页：产 PAGE_IMAGE，元素 assetRef 可读到 PNG 字节。 */
    @Test
    void fullPageImageShouldBeWrittenAsPageAsset() throws Exception {
        RecordingAssetSink sink = new RecordingAssetSink();
        ParseSource source = parse(buildFullPageImagePdf(), sink);

        ParseElement image = imageElements(source).get(0);
        assertNotNull(image.getAssetRef());
        ParseAsset pageAsset = sink.assets().stream()
                .filter(asset -> ParseAssetKind.PAGE_IMAGE == asset.getKind())
                .findFirst().orElseThrow();
        assertEquals(1, pageAsset.getPage());
        assertEquals(image.getAssetRef(), pageAsset.getAssetId());

        byte[] content = sink.bytes(image.getAssetRef());
        assertNotNull(content);
        assertArrayEquals(new byte[]{(byte) 0x89, 'P', 'N', 'G'}, java.util.Arrays.copyOf(content, 4));
    }

    /** 正文 + 大图：不产页图，产 IMAGE_CROP，元素 assetRef 指向裁剪图。 */
    @Test
    void textPageImageShouldBeWrittenAsCropAsset() throws Exception {
        RecordingAssetSink sink = new RecordingAssetSink();
        ParseSource source = parse(buildTextWithImagePdf(), sink);

        ParseElement image = imageElements(source).get(0);
        assertNotNull(image.getAssetRef());
        ParseAsset crop = sink.assets().stream()
                .filter(asset -> ParseAssetKind.IMAGE_CROP == asset.getKind())
                .findFirst().orElseThrow();
        assertEquals(image.getAssetRef(), crop.getAssetId());
        assertTrue(sink.assets().stream().noneMatch(asset -> ParseAssetKind.PAGE_IMAGE == asset.getKind()));
    }

    private ParseSource parse(byte[] data, RecordingAssetSink sink) {
        ParseContext context = new ParseContext();
        FileReference fileRef = new FileReference();
        fileRef.setFileId("F-1");
        fileRef.setFileName("assets.pdf");
        fileRef.setMimeType("application/pdf");
        context.setFileRef(fileRef);
        context.setInputStream(new ByteArrayInputStream(data));
        context.setProperties(PropertiesFixtures.parse());
        context.setAssetSink(sink);
        return new PdfBoxDocumentParser(new ProjectionPageLayoutAnalyzer()).parse(context);
    }

    private List<ParseElement> imageElements(ParseSource source) {
        List<ParseElement> images = source.getElements().stream()
                .filter(element -> ElementType.IMAGE.name().equals(element.getType()))
                .toList();
        assertEquals(1, images.size());
        return images;
    }

    private byte[] buildFullPageImagePdf() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(595, 842));
            doc.addPage(page);
            PDImageXObject xobject = LosslessFactory.createFromImage(doc,
                    new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB));
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(xobject, 0, 0, 595, 842);
            }
            return PdfParserTestFixture.save(doc);
        }
    }

    private byte[] buildTextWithImagePdf() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(595, 842));
            doc.addPage(page);
            PDImageXObject xobject = LosslessFactory.createFromImage(doc, PdfParserTestFixture.sampleImage());
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                        org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 10);
                cs.beginText();
                cs.newLineAtOffset(72, 700);
                cs.showText("body text for image asset page");
                cs.endText();
                cs.drawImage(xobject, 40, 100, 300, 300);
            }
            return PdfParserTestFixture.save(doc);
        }
    }
}
