package com.workbuddy.worker.parser.office;

import com.workbuddy.common.domain.input.FileReference;
import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.enums.input.FileFormat;
import com.workbuddy.common.enums.parse.ElementType;
import com.workbuddy.common.enums.parse.ParseAssetKind;
import com.workbuddy.worker.parser.ParseContext;
import com.workbuddy.worker.parser.office.impl.DocxDocumentParser;
import com.workbuddy.worker.testkit.PropertiesFixtures;
import com.workbuddy.worker.testkit.RecordingAssetSink;
import org.apache.poi.common.usermodel.PictureType;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DOCX 图片资产：嵌入图片原始字节写入资产，元素 assetRef 指向资产 ID。
 */
class DocxImageAssetTest {

    /** 校验点：文档内图片写 {@code ParseAssetKind#EMBEDDED_IMAGE}，{@code ParseElement#assetRef} 可读回原字节。 */
    @Test
    void embeddedPictureShouldBeWrittenAsAsset() throws Exception {
        byte[] png = pngBytes();
        RecordingAssetSink sink = new RecordingAssetSink();
        ParseSource source = new DocxDocumentParser().parse(context(buildDocx(png), sink));

        ParseElement image = source.getElements().stream()
                .filter(element -> ElementType.IMAGE.name().equals(element.getType()))
                .findFirst().orElseThrow();
        assertNotNull(image.getAssetRef());
        assertTrue(sink.assets().stream().anyMatch(asset -> ParseAssetKind.EMBEDDED_IMAGE == asset.getKind()));
        assertArrayEquals(png, sink.bytes(image.getAssetRef()));
    }

    private ParseContext context(byte[] data, RecordingAssetSink sink) {
        ParseContext context = new ParseContext();
        FileReference fileRef = new FileReference();
        fileRef.setFileId("F-1");
        fileRef.setFileName("assets.docx");
        fileRef.setMimeType(FileFormat.DOCX.getMimeType());
        context.setFileRef(fileRef);
        context.setInputStream(new ByteArrayInputStream(data));
        context.setProperties(PropertiesFixtures.parse());
        context.setAssetSink(sink);
        return context;
    }

    private byte[] buildDocx(byte[] png) throws Exception {
        try (XWPFDocument doc = new XWPFDocument()) {
            var paragraph = doc.createParagraph();
            var run = paragraph.createRun();
            run.setText("image: ");
            run.addPicture(new ByteArrayInputStream(png), PictureType.PNG, "image.png",
                    Units.toEMU(100), Units.toEMU(100));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            return out.toByteArray();
        }
    }

    private byte[] pngBytes() throws Exception {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", out);
            return out.toByteArray();
        }
    }
}
