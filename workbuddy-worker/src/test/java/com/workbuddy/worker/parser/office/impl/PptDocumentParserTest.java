package com.workbuddy.worker.parser.office.impl;

import com.workbuddy.common.domain.input.FileReference;
import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.enums.parse.ElementType;
import com.workbuddy.worker.parser.ParseContext;
import com.workbuddy.worker.parser.ParseProperties;
import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PPTX 解析器单测：文本框成段落、表格成 TABLE（表头判定走统一口径）、图片成 IMAGE 并按页记 OCR_IMAGE 事实；
 * 页单位为幻灯片，页级指标按幻灯片回填。
 *
 * @author cxxl
 */
class PptDocumentParserTest {

    private static final String MIME_PPTX =
            "application/vnd.openxmlformats-officedocument.presentationml.presentation";

    private final PptDocumentParser parser = new PptDocumentParser();

    /** 一页含文本框 + 表格 + 图片：元素与事实齐备，指标按页回填 */
    @Test
    void pptxSlideShouldYieldTextTablePictureAndMetrics() throws Exception {
        ParseSource source = parser.parse(context(buildPptx()));

        List<ParseElement> paragraphs = elements(source, ElementType.PARAGRAPH);
        assertEquals(1, paragraphs.size(), () -> "elements=" + source.getElements());
        assertEquals("季度汇报", paragraphs.getFirst().getText());
        assertEquals(1, paragraphs.getFirst().getPage());

        List<ParseElement> tables = elements(source, ElementType.TABLE);
        assertEquals(1, tables.size());
        assertEquals(2, tables.getFirst().getRows());
        assertEquals(2, tables.getFirst().getCols());
        assertEquals(0, tables.getFirst().getHeaderRow());
        assertEquals(4, tables.getFirst().getCells().size());

        List<ParseElement> images = elements(source, ElementType.IMAGE);
        assertEquals(1, images.size());
        assertEquals(Boolean.TRUE, images.getFirst().getNeedsOcr());
        assertTrue(images.getFirst().getAssetRef().startsWith("image-0."),
                () -> "asset=" + images.getFirst().getAssetRef());
        assertTrue(source.getFacts().stream().anyMatch(fact -> fact.getEvidence().contains("本页 1 张")),
                () -> "facts=" + source.getFacts());

        assertEquals(1, source.getUnitCount());
        assertEquals(1, source.getPageMetrics().getFirst().getPage());
        assertTrue(source.getPageMetrics().getFirst().getCharCount() > 0);
        assertEquals(1, source.getPageMetrics().getFirst().getImageCount());
    }

    /** MIME 支持两种演示格式 */
    @Test
    void supportsShouldAcceptPptAndPptx() {
        assertTrue(parser.supports(MIME_PPTX));
        assertTrue(parser.supports("application/vnd.ms-powerpoint"));
    }

    private List<ParseElement> elements(ParseSource source, ElementType type) {
        return source.getElements().stream().filter(e -> type.name().equals(e.getType())).toList();
    }

    private ParseContext context(byte[] data) {
        ParseContext context = new ParseContext();
        FileReference fileRef = new FileReference();
        fileRef.setFileId("F-1");
        fileRef.setFileName("deck.pptx");
        fileRef.setMimeType(MIME_PPTX);
        context.setFileRef(fileRef);
        context.setInputStream(new ByteArrayInputStream(data));
        context.setProperties(new ParseProperties());
        return context;
    }

    /** 造一页：文本框 + 2×2 表格 + 一张 1×1 PNG */
    private byte[] buildPptx() throws Exception {
        try (XMLSlideShow show = new XMLSlideShow()) {
            XSLFSlide slide = show.createSlide();
            XSLFTextBox box = slide.createTextBox();
            box.setText("季度汇报");

            XSLFTable table = slide.createTable();
            XSLFTableRow header = table.addRow();
            header.addCell().setText("评分项");
            header.addCell().setText("分值");
            XSLFTableRow data = table.addRow();
            data.addCell().setText("A1 报价");
            data.addCell().setText("30");

            byte[] png = Base64.getDecoder().decode(
                    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg==");
            XSLFPictureData picture = show.addPicture(png, PictureData.PictureType.PNG);
            slide.createPicture(picture);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            show.write(out);
            return out.toByteArray();
        }
    }
}
