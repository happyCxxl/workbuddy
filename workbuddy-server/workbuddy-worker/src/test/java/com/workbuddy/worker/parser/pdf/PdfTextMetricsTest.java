package com.workbuddy.worker.parser.pdf;

import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.domain.parse.signal.PageMetric;
import com.workbuddy.common.enums.parse.ElementType;
import com.workbuddy.common.enums.parse.SignalType;
import com.workbuddy.worker.parser.pdf.extract.LineTexts;
import com.workbuddy.worker.parser.pdf.model.CharInfo;
import com.workbuddy.worker.parser.pdf.model.PageLine;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import com.workbuddy.worker.testkit.PropertiesFixtures;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PDF 文本与页级指标单测：行文本重建、字符/图片/矢量覆盖率、图片元素与事实。
 */
class PdfTextMetricsTest {

    private final PdfParserTestFixture parser = new PdfParserTestFixture();
    private final PdfMetricsPageFixture pages = new PdfMetricsPageFixture();

    /**
     * 守护 {@code scannedPdfShouldEmitZeroCharMetrics}：该场景行为改动即失败。
     */
    @Test
    void scannedPdfShouldEmitZeroCharMetrics() throws Exception {
        // 空白页（无文本层）→ 页级字符数为 0（扫描判定交给 SignalDetector）
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(new PDRectangle(595, 842)));
            ParseSource source = parser.parse(PdfParserTestFixture.save(doc));
            assertEquals(1, source.getUnitCount());
            assertEquals(0, source.getPageMetrics().getFirst().getCharCount());
        }
    }

    /** 行文本重建阈值：取解析阈值默认值 */
    private LineTexts.Options lineTextOptions() {
        return LineTexts.Options.of(PropertiesFixtures.parse());
    }

    /**
     * 守护 {@code englishWordBoundariesShouldKeepSpaces}：该场景行为改动即失败。
     */
    @Test
    void englishWordBoundariesShouldKeepSpaces() {
        List<CharInfo> chars = new ArrayList<>();
        chars.addAll(word("Chapter", 72));
        chars.addAll(word("one", 110));
        chars.addAll(word("body", 130));

        assertEquals("Chapter one body", LineTexts.lineText(chars, lineTextOptions()));
    }

    /**
     * 守护 {@code cjkWordBoundariesShouldNotInsertSpaces}：该场景行为改动即失败。
     */
    @Test
    void cjkWordBoundariesShouldNotInsertSpaces() {
        List<CharInfo> chars = new ArrayList<>();
        // 每个汉字各成一次回调（PDFBox 对 CJK 常逐字回调）
        chars.add(ch('知', 72));
        chars.add(ch('识', 82));
        chars.add(ch('库', 92));

        assertEquals("知识库", LineTexts.lineText(chars, lineTextOptions()));
    }

    /**
     * 守护 {@code mixedScriptBoundaryShouldKeepSpace}：该场景行为改动即失败。
     */
    @Test
    void mixedScriptBoundaryShouldKeepSpace() {
        List<CharInfo> chars = new ArrayList<>();
        chars.add(ch('知', 72));
        chars.add(ch('识', 82));
        chars.add(ch('库', 92));
        chars.addAll(word("knowledge", 110));

        assertEquals("知识库 knowledge", LineTexts.lineText(chars, lineTextOptions()));
    }

    /**
     * 守护 {@code lineSeparatorShouldFollowCjkRule}：该场景行为改动即失败。
     */
    @Test
    void lineSeparatorShouldFollowCjkRule() {
        assertTrue(LineTexts.lineSeparator(line("知识库"), line("下一句")).isEmpty());
        assertEquals(" ", LineTexts.lineSeparator(line("Chapter one"), line("body")));
    }

    /**
     * 守护 {@code cjkCodePointShouldCoverHanAndFullwidthPunctuation}：该场景行为改动即失败。
     */
    @Test
    void cjkCodePointShouldCoverHanAndFullwidthPunctuation() {
        assertTrue(LineTexts.isCjk('中'));
        assertTrue(LineTexts.isCjk('。'));
        assertTrue(LineTexts.isCjk('，'));
        assertTrue(LineTexts.isCjk('ア'));
        assertFalse(LineTexts.isCjk('A'));
        assertFalse(LineTexts.isCjk(','));
    }

    /**
     * 守护 {@code fullPageImageShouldBeMeasuredAsImageCoverage}：该场景行为改动即失败。
     */
    @Test
    void fullPageImageShouldBeMeasuredAsImageCoverage() throws Exception {
        ParseSource source = parser.parse(pages.buildFullPageImagePdf());

        PageMetric metric = source.getPageMetrics().getFirst();
        assertEquals(0, metric.getCharCount());
        assertTrue(metric.getImageAreaRatio() > 0.5, () -> "image=" + metric.getImageAreaRatio());
    }

    /**
     * 守护 {@code pageImageShouldEmitImageElementAndFact}：该场景行为改动即失败。
     */
    @Test
    void pageImageShouldEmitImageElementAndFact() throws Exception {
        ParseSource source = parser.parse(pages.buildFullPageImagePdf());

        ParseElement image = source.getElements().stream()
                .filter(e -> ElementType.IMAGE.name().equals(e.getType()))
                .findFirst().orElse(null);
        assertNotNull(image, () -> "elements=" + source.getElements());
        assertEquals(1, image.getPage());
        assertTrue(image.getNeedsOcr());
        assertNotNull(image.getBbox());
        assertTrue(image.getProvenance().getPath().contains("/image[0]"));
        assertTrue(source.getFacts().stream().anyMatch(f -> SignalType.OCR_IMAGE == f.getType()));
    }

    /**
     * 守护 {@code smallPageImageShouldNotEmitElement}：该场景行为改动即失败。
     */
    @Test
    void smallPageImageShouldNotEmitElement() throws Exception {
        ParseSource source = parser.parse(pages.buildSmallImagePdf());

        // 30×30pt 小图占页面积约 0.2%，低于图片元素门槛
        assertTrue(source.getElements().stream()
                .noneMatch(e -> ElementType.IMAGE.name().equals(e.getType())));
        assertTrue(source.getFacts().isEmpty(), () -> "facts=" + source.getFacts());
    }

    /** 多图页：图片元素逐张产出，OCR_IMAGE 事实按页聚合成一条（证据带张数）（{@code multipleImagesOnPageShouldEmitSingleImageFact}）。 */
    @Test
    void multipleImagesOnPageShouldEmitSingleImageFact() throws Exception {
        ParseSource source = parser.parse(pages.buildTwoImagePdf());

        List<ParseElement> images = source.getElements().stream()
                .filter(e -> ElementType.IMAGE.name().equals(e.getType())).toList();
        assertEquals(2, images.size(), () -> "elements=" + source.getElements());
        var facts = source.getFacts().stream().filter(f -> SignalType.OCR_IMAGE == f.getType()).toList();
        assertEquals(1, facts.size(), () -> "facts=" + source.getFacts());
        assertEquals("page 1", facts.getFirst().getRegion());
        assertTrue(facts.getFirst().getEvidence().contains("2 张"),
                () -> "evidence=" + facts.getFirst().getEvidence());
    }

    /** 纯矢量页：无文本无图片但成框的矢量线撑出成片覆盖 → 页级指标给出矢量覆盖率（{@code vectorOnlyPageShouldReportVectorAreaRatio}）。 */
    @Test
    void vectorOnlyPageShouldReportVectorAreaRatio() throws Exception {
        ParseSource source = parser.parse(pages.buildVectorChartPage());

        PageMetric metric = source.getPageMetrics().getFirst();
        assertEquals(0, metric.getCharCount());
        assertEquals(0.0, metric.getImageAreaRatio(), 0.0001);
        assertTrue(metric.getVectorAreaRatio() >= 0.05, () -> "vector=" + metric.getVectorAreaRatio());
    }

    /** 造一个词的字符序列：词内 x 连续，首末码点带词边界标记 */
    private List<CharInfo> word(String text, double x) {
        List<CharInfo> chars = new ArrayList<>();
        for (int i = 0; i < text.length(); i++) {
            chars.add(ch(text.charAt(i), x + i * 5));
        }
        return chars;
    }

    /** 造一个字符事实（固定行高与字号） */
    private CharInfo ch(int codePoint, double x) {
        return new CharInfo(x, 700, 5, 10, 10, "Helvetica", false, codePoint);
    }

    /** 造一个行事实（只用到文本） */
    private PageLine line(String text) {
        return new PageLine(1, 72, 700, 100, 10, text, 10, "Helvetica", false,
                List.of(), List.of(), false);
    }
}
