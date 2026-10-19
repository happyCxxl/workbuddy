package com.workbuddy.worker.parser.pdf;

import com.workbuddy.common.domain.input.FileReference;
import com.workbuddy.common.domain.parse.BBox;
import com.workbuddy.common.domain.parse.PageDimension;
import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.worker.parser.ParseContext;
import com.workbuddy.worker.parser.ParseProperties;
import com.workbuddy.worker.parser.pdf.layout.ProjectionPageLayoutAnalyzer;
import com.workbuddy.worker.parser.pdf.model.PdfPageGeometry;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 页几何单测：页尺寸与元素 bbox 必须是**视口坐标**（与 pdf.js `getViewport({scale:1})` 同口径）。
 * 期望值取自 pdf.js 对同批样例的实测（`getTextContent` 项变换到视口空间）；
 * 样例文件见 samples/pdf-parse-03。
 */
class PdfPageGeometryTest {

    /** A4 实测尺寸 595.276×841.89，容差留到 0.5 */
    private static final double DELTA = 0.5;

    private final PdfBoxDocumentParser parser =
            new PdfBoxDocumentParser(new ProjectionPageLayoutAnalyzer());

    /**
     * 守护 {@code viewportMappingShouldFollowRotation}：该场景行为改动即失败。
     */
    @Test
    void viewportMappingShouldFollowRotation() {
        PdfPageGeometry none = new PdfPageGeometry(595, 842, 0);
        assertEquals(595, none.viewportWidth(), DELTA);
        assertEquals(842, none.viewportHeight(), DELTA);
        assertEquals(10, none.toViewport(new BBox(10, 20, 30, 40)).getX(), DELTA);

        // 90 度：宽高互换，版面 (x,y) 落到视口 (高-y, x)
        PdfPageGeometry right = new PdfPageGeometry(595, 842, 90);
        assertEquals(842, right.viewportWidth(), DELTA);
        assertEquals(595, right.viewportHeight(), DELTA);
        BBox box90 = right.toViewport(new BBox(120, 141.9, 138.5, 8.1));
        assertEquals(842 - 141.9 - 8.1, box90.getX(), DELTA);
        assertEquals(120, box90.getY(), DELTA);
        assertEquals(8.1, box90.getWidth(), DELTA);
        assertEquals(138.5, box90.getHeight(), DELTA);

        // 180 度：宽高不变，两个方向都镜像
        PdfPageGeometry half = new PdfPageGeometry(595, 842, 180);
        BBox box180 = half.toViewport(new BBox(120, 141.9, 138.5, 8.1));
        assertEquals(595 - 120 - 138.5, box180.getX(), DELTA);
        assertEquals(842 - 141.9 - 8.1, box180.getY(), DELTA);

        // 270 度：宽高互换，版面 (x,y) 落到视口 (y, 宽-x)
        PdfPageGeometry left = new PdfPageGeometry(595, 842, 270);
        assertEquals(842, left.viewportWidth(), DELTA);
        BBox box270 = left.toViewport(new BBox(120, 141.9, 138.5, 8.1));
        assertEquals(141.9, box270.getX(), DELTA);
        assertEquals(595 - 120 - 138.5, box270.getY(), DELTA);

        // 非 90 倍数按 0 处理（pdf.js 对这种页直接报错）
        assertEquals(0, new PdfPageGeometry(595, 842, 45).rotation());
    }

    /**
     * 守护 {@code rotatedPageShouldReportViewportGeometry}：该场景行为改动即失败。
     */
    @Test
    void rotatedPageShouldReportViewportGeometry() throws Exception {
        assertPage(0, 595, 842, 120.0, 141.9, 138.5, 8.1);
        assertPage(90, 842, 595, 691.9, 120.0, 8.1, 138.5);
        assertPage(180, 595, 842, 336.8, 691.9, 138.5, 8.1);
        assertPage(270, 842, 595, 141.9, 336.8, 8.1, 138.5);
    }

    /**
     * 守护 {@code cropBoxShouldDrivePageSizeAndKeepCoordinates}：该场景行为改动即失败。
     */
    @Test
    void cropBoxShouldDrivePageSizeAndKeepCoordinates() throws Exception {
        byte[] data = pdf(0, new PDRectangle(72, 72, 468, 720));
        ParseSource source = parse(data);

        assertEquals(468, dimension(source).getWidth(), DELTA);
        assertEquals(720, dimension(source).getHeight(), DELTA);
        BBox bbox = paragraph(source).getBbox();
        assertEquals(48.0, bbox.getX(), DELTA);
        assertEquals(92.0, bbox.getY(), DELTA);
        assertEquals("Quarterly Report 2024", paragraph(source).getText());
    }

    /**
     * 守护 {@code rotatedCropShouldMapBothAxes}：该场景行为改动即失败。
     */
    @Test
    void rotatedCropShouldMapBothAxes() throws Exception {
        byte[] data = pdf(90, new PDRectangle(72, 72, 468, 720));
        ParseSource source = parse(data);

        assertEquals(720, dimension(source).getWidth(), DELTA);
        assertEquals(468, dimension(source).getHeight(), DELTA);
        BBox bbox = paragraph(source).getBbox();
        assertEquals(619.9, bbox.getX(), DELTA);
        assertEquals(48.0, bbox.getY(), DELTA);
        assertEquals(8.1, bbox.getWidth(), DELTA);
    }

    /** 旋转页文本不得被拆出词内空格（词边界来自几何，不依赖提取库在旋转页上失真的词回调）（{@code rotatedPageTextShouldKeepWordsWhole}）。 */
    @Test
    void rotatedPageTextShouldKeepWordsWhole() throws Exception {
        for (int rotation : new int[] {0, 90, 180, 270}) {
            assertEquals("Quarterly Report 2024", paragraph(parse(pdf(rotation, null))).getText(),
                    () -> "rotation=" + rotation);
        }
    }

    private void assertPage(int rotation, double width, double height, double x, double y,
                            double w, double h) throws Exception {
        ParseSource source = parse(pdf(rotation, null));
        assertEquals(width, dimension(source).getWidth(), DELTA, () -> "rotation=" + rotation);
        assertEquals(height, dimension(source).getHeight(), DELTA, () -> "rotation=" + rotation);
        BBox bbox = paragraph(source).getBbox();
        assertEquals(x, bbox.getX(), DELTA, () -> "rotation=" + rotation);
        assertEquals(y, bbox.getY(), DELTA, () -> "rotation=" + rotation);
        assertEquals(w, bbox.getWidth(), DELTA, () -> "rotation=" + rotation);
        assertEquals(h, bbox.getHeight(), DELTA, () -> "rotation=" + rotation);
    }

    private PageDimension dimension(ParseSource source) {
        return source.getPageDimensions().getFirst();
    }

    private ParseElement paragraph(ParseSource source) {
        List<ParseElement> paragraphs = source.getElements().stream()
                .filter(element -> "PARAGRAPH".equals(element.getType()))
                .toList();
        assertEquals(1, paragraphs.size(), () -> "elements=" + source.getElements());
        return paragraphs.getFirst();
    }

    private ParseSource parse(byte[] data) {
        ParseContext context = new ParseContext();
        FileReference fileRef = new FileReference();
        fileRef.setFileId("F-geometry");
        fileRef.setFileName("geometry.pdf");
        fileRef.setMimeType("application/pdf");
        context.setFileRef(fileRef);
        context.setInputStream(new ByteArrayInputStream(data));
        context.setProperties(new ParseProperties());
        return parser.parse(context);
    }

    /** 一行文字「Quarterly Report 2024」放在版面 (120, 700)；rotation 为页旋转，crop 为空时用媒体框 */
    private byte[] pdf(int rotation, PDRectangle crop) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            if (crop != null) {
                page.setCropBox(crop);
            }
            page.setRotation(rotation);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                cs.newLineAtOffset(120, 700);
                cs.showText("Quarterly Report 2024");
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}
