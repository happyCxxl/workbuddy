package com.workbuddy.worker.parser;

import com.workbuddy.common.domain.input.FileReference;
import com.workbuddy.common.domain.parse.CapabilitySnapshot;
import com.workbuddy.common.domain.parse.ParseOutcome;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.domain.parse.capability.CapabilityUsage;
import com.workbuddy.common.domain.parse.capability.OcrLine;
import com.workbuddy.common.domain.parse.capability.StructuredOcrResult;
import com.workbuddy.common.domain.parse.signal.PageMetric;
import com.workbuddy.worker.parser.capability.CapabilityRegistry;
import com.workbuddy.worker.parser.capability.LayoutProvider;
import com.workbuddy.worker.parser.capability.StructuredOcrProvider;
import com.workbuddy.worker.parser.capability.TableStructureProvider;
import com.workbuddy.common.domain.parse.signal.ParseFact;
import com.workbuddy.common.enums.task.PipelineTaskErrorCode;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.enums.parse.SignalType;
import com.workbuddy.worker.parser.DocumentParserPort;
import com.workbuddy.worker.parser.ParseContext;
import com.workbuddy.worker.parser.ParseProperties;
import com.workbuddy.worker.parser.signal.impl.SignalDetectorImpl;
import com.workbuddy.worker.parser.signal.SignalFallbackHandler;
import com.workbuddy.worker.parser.signal.fallback.BlankFallbackHandler;
import com.workbuddy.worker.parser.signal.fallback.CidMappingFallbackHandler;
import com.workbuddy.worker.parser.signal.fallback.GarbledFallbackHandler;
import com.workbuddy.worker.parser.signal.fallback.ImageEmbeddedFallbackHandler;
import com.workbuddy.worker.parser.signal.fallback.ImageLowRatioFallbackHandler;
import com.workbuddy.worker.parser.signal.fallback.LayoutRuleFailedFallbackHandler;
import com.workbuddy.worker.parser.signal.fallback.ScannedFallbackHandler;
import com.workbuddy.worker.parser.signal.fallback.TableRuleFailedFallbackHandler;
import com.workbuddy.worker.parser.signal.fallback.VectorPageFallbackHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 解析管线编排单测：路由、门槛三档、纯扫描件、信号降级处置（六 handler 真实实例）。
 */
@ExtendWith(MockitoExtension.class)
class ParsePipelineTest {

    private ParseProperties properties;
    private SignalDetectorImpl signalDetector;

    @BeforeEach
    void setUp() {
        properties = new ParseProperties();
        signalDetector = new SignalDetectorImpl();
    }

    /** 九个信号降级 handler 真实实例（无 mock） */
    private List<SignalFallbackHandler> fallbackHandlers() {
        return List.of(new GarbledFallbackHandler(), new CidMappingFallbackHandler(), new ScannedFallbackHandler(),
                new BlankFallbackHandler(), new VectorPageFallbackHandler(), new ImageEmbeddedFallbackHandler(),
                new ImageLowRatioFallbackHandler(), new TableRuleFailedFallbackHandler(),
                new LayoutRuleFailedFallbackHandler());
    }

    private ParseContext context(String mimeType) {
        ParseContext context = new ParseContext();
        context.setFileResultId(1L);
        FileReference fileRef = new FileReference();
        fileRef.setFileId("F-1");
        fileRef.setFileName("t");
        fileRef.setMimeType(mimeType);
        context.setFileRef(fileRef);
        context.setInputStream(new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)));
        context.setProperties(properties);
        return context;
    }

    private DocumentParserPort fakeParser(List<PageMetric> metrics, List<ParseFact> facts) {
        return fakeParser(metrics, facts, List.of());
    }

    /** 假解析器（可带本路用到的能力引用） */
    private DocumentParserPort fakeParser(List<PageMetric> metrics, List<ParseFact> facts,
                                          List<CapabilityUsage> capabilities) {
        return new DocumentParserPort() {
            @Override
            public boolean supports(String mime) {
                return "application/pdf".equals(mime);
            }

            @Override
            public ParseSource parse(ParseContext context) {
                ParseSource source = ParseSource.nativeSource("fake-1.0");
                source.setUnitCount(metrics.size());
                source.setPageMetrics(metrics);
                source.setFacts(facts);
                source.setCapabilities(capabilities);
                return source;
            }

            @Override
            public String capabilityName() {
                return "fake";
            }

            @Override
            public String capabilityVersion() {
                return "1.0";
            }
        };
    }

    private ParsePipeline pipeline(DocumentParserPort parser) {
        return pipeline(parser, null);
    }

    /** 管线（可带能力注册表） */
    private ParsePipeline pipeline(DocumentParserPort parser, CapabilityRegistry capabilityRegistry) {
        return new ParsePipeline(List.of(parser), signalDetector, fallbackHandlers(), properties, capabilityRegistry);
    }

    /** OCR 路由：整页无文本且含图片的页交能力，识别结果进 ocr 路并回填快照的 ocr 项（{@code scannedPageShouldBeFilledIntoOcrSource}）。 */
    @Test
    void scannedPageShouldBeFilledIntoOcrSource() {
        PageMetric scanned = new PageMetric();
        scanned.setPage(1);
        scanned.setCharCount(0);
        scanned.setImageCount(1);
        ParsePipeline pipeline = pipeline(fakeParser(List.of(scanned), List.of()), stubOcrRegistry());

        ParseOutcome outcome = pipeline.run(context("application/pdf"));

        ParseSource ocr = outcome.getParseResult().getSources().get(1);
        assertEquals("ocr", ocr.getSource());
        assertEquals(2, ocr.getElements().size(), () -> "elements=" + ocr.getElements());
        assertEquals(1, ocr.getElements().getFirst().getPage());
        assertEquals(Boolean.FALSE, ocr.getElements().getFirst().getNeedsOcr());
        assertEquals(0.94, ocr.getElements().getFirst().getConfidence(), 0.0001);
        assertTrue(ocr.getFacts().stream().anyMatch(fact -> fact.getEvidence().contains("文字由能力补出")),
                () -> "facts=" + ocr.getFacts());
        assertEquals("stub-ocr", ocr.getCapabilities().getFirst().model());
        assertEquals("stub-ocr", outcome.getParseResult().getCapabilitySnapshot().getOcr().getModel());
    }

    /** 未接入 OCR：ocr 路保持预留占位（无元素、文案不变）（{@code withoutOcrCapabilityShouldKeepPlaceholderSource}）。 */
    @Test
    void withoutOcrCapabilityShouldKeepPlaceholderSource() {
        PageMetric scanned = new PageMetric();
        scanned.setPage(1);
        scanned.setCharCount(0);
        scanned.setImageCount(1);
        ParsePipeline pipeline = pipeline(fakeParser(List.of(scanned), List.of()));

        ParseOutcome outcome = pipeline.run(context("application/pdf"));

        ParseSource ocr = outcome.getParseResult().getSources().get(1);
        assertEquals("ocr", ocr.getSource());
        assertTrue(ocr.getElements().isEmpty());
        assertTrue(ocr.getNote().contains("预留未开放"), () -> "note=" + ocr.getNote());
        assertNull(outcome.getParseResult().getCapabilitySnapshot().getOcr());
    }

    /** 只接 OCR 能力的注册表：桩为每页返回两行（页码随区域走） */
    private CapabilityRegistry stubOcrRegistry() {
        return new CapabilityRegistry() {
            @Override
            public StructuredOcrProvider ocr() {
                return (region, ctx) -> {
                    StructuredOcrResult result = new StructuredOcrResult();
                    result.setModel("stub-ocr");
                    result.setVersion("1.2");
                    OcrLine first = new OcrLine();
                    first.setText("invoice no");
                    first.setConfidence(0.94);
                    OcrLine second = new OcrLine();
                    second.setText("total 30");
                    second.setConfidence(0.88);
                    result.setLines(List.of(first, second));
                    return result;
                };
            }

            @Override
            public LayoutProvider layout() {
                return null;
            }

            @Override
            public TableStructureProvider table() {
                return null;
            }
        };
    }

    private PageMetric normalPage(int page) {
        PageMetric metric = new PageMetric();
        metric.setPage(page);
        metric.setCharCount(500);
        metric.setGarbledRatio(0.01);
        metric.setTextAreaRatio(0.3);
        return metric;
    }

    /** 扫描页：整页无文本 + 图片覆盖高 */
    private PageMetric scannedPage(int page) {
        PageMetric metric = new PageMetric();
        metric.setPage(page);
        metric.setCharCount(0);
        metric.setGarbledRatio(0);
        metric.setTextAreaRatio(0);
        metric.setImageAreaRatio(0.8);
        return metric;
    }

    /** 空白页：整页无文本也无图片 */
    private PageMetric blankPage(int page) {
        PageMetric metric = new PageMetric();
        metric.setPage(page);
        metric.setCharCount(0);
        metric.setGarbledRatio(0);
        metric.setTextAreaRatio(0);
        return metric;
    }

    /** 文字极少页：有文本但少于扫描页门槛且含图片（疑似图片单元，仅告警） */
    private PageMetric sparseTextPage(int page) {
        PageMetric metric = new PageMetric();
        metric.setPage(page);
        metric.setCharCount(10);
        metric.setGarbledRatio(0);
        metric.setTextAreaRatio(0.3);
        metric.setImageCount(1);
        return metric;
    }

    /** Office 图片单元：整单元无文本 + 内嵌图片（Word 全图档 / 只放图的 sheet） */
    private PageMetric imageOnlyUnit(int unit) {
        PageMetric metric = new PageMetric();
        metric.setPage(unit);
        metric.setCharCount(0);
        metric.setImageCount(1);
        return metric;
    }

    private PageMetric garbledPage() {
        PageMetric metric = new PageMetric();
        metric.setPage(1);
        metric.setCharCount(500);
        metric.setGarbledRatio(0.9);
        metric.setTextAreaRatio(0.3);
        return metric;
    }

    /** 乱码页且字符主要来自缺 Unicode 映射的 CID 字体 */
    private PageMetric cidMappingFailedPage() {
        PageMetric metric = garbledPage();
        metric.setCidMappingRatio(0.95);
        return metric;
    }

    /**
     * 守护 {@code allPagesOkShouldSuggestSuccess}：该场景行为改动即失败。
     */
    @Test
    void allPagesOkShouldSuggestSuccess() {
        ParsePipeline pipeline = pipeline(fakeParser(
                List.of(normalPage(1), normalPage(2)), List.of()));

        ParseOutcome outcome = pipeline.run(context("application/pdf"));

        assertEquals(PipelineTaskStatus.SUCCESS, outcome.getSuggestedStatus());
        assertEquals(0, outcome.getFailedUnits());
        assertNotNull(outcome.getParseResult().getCapabilitySnapshot());
        assertEquals(3, outcome.getStepLogs().size());
        assertEquals(2, outcome.getParseResult().getSources().size());
        assertEquals("ocr", outcome.getParseResult().getSources().get(1).getSource());
    }

    /**
     * 守护 {@code oneScannedPageOutOfTenShouldSuggestPartialSuccess}：该场景行为改动即失败。
     */
    @Test
    void oneScannedPageOutOfTenShouldSuggestPartialSuccess() {
        List<PageMetric> metrics = new java.util.ArrayList<>();
        metrics.add(scannedPage(1));
        for (int i = 2; i <= 10; i++) {
            metrics.add(normalPage(i));
        }
        ParsePipeline pipeline = pipeline(fakeParser(metrics, List.of()));

        ParseOutcome outcome = pipeline.run(context("application/pdf"));

        assertEquals(PipelineTaskStatus.PARTIAL_SUCCESS, outcome.getSuggestedStatus());
        assertEquals(1, outcome.getFailedUnits());
        assertEquals(1, outcome.getParseResult().getQuality().getScannedPages().size());
        assertEquals(1, outcome.getParseResult().getQuality().getWarnings().size());
    }

    /**
     * 守护 {@code allScannedShouldFailScannedUnsupported}：该场景行为改动即失败。
     */
    @Test
    void allScannedShouldFailScannedUnsupported() {
        ParsePipeline pipeline = pipeline(fakeParser(
                List.of(scannedPage(1), scannedPage(2), scannedPage(3)), List.of()));

        ParseOutcome outcome = pipeline.run(context("application/pdf"));

        assertEquals(PipelineTaskStatus.FAILED, outcome.getSuggestedStatus());
        assertEquals(PipelineTaskErrorCode.SCANNED_UNSUPPORTED.name(), outcome.getErrorCode());
    }

    /**
     * 守护 {@code ratioBelowThresholdShouldFail}：该场景行为改动即失败。
     */
    @Test
    void ratioBelowThresholdShouldFail() {
        List<PageMetric> metrics = new java.util.ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            metrics.add(scannedPage(i));
        }
        for (int i = 6; i <= 10; i++) {
            metrics.add(normalPage(i));
        }
        ParsePipeline pipeline = pipeline(fakeParser(metrics, List.of()));

        ParseOutcome outcome = pipeline.run(context("application/pdf"));

        assertEquals(PipelineTaskStatus.FAILED, outcome.getSuggestedStatus());
        assertEquals(PipelineTaskErrorCode.RATIO_BELOW_THRESHOLD.name(), outcome.getErrorCode());
    }

    /** 短文档（≤3 单元）坏页 1 张：按坏页绝对数放行，不落 RATIO_BELOW_THRESHOLD（{@code shortDocumentWithOneBadPageShouldBePartialSuccess}）。 */
    @Test
    void shortDocumentWithOneBadPageShouldBePartialSuccess() {
        ParsePipeline pipeline = pipeline(fakeParser(
                List.of(garbledPage(), normalPage(2), normalPage(3)), List.of()));

        ParseOutcome outcome = pipeline.run(context("application/pdf"));

        assertEquals(PipelineTaskStatus.PARTIAL_SUCCESS, outcome.getSuggestedStatus());
        assertEquals(1, outcome.getFailedUnits());
        assertNull(outcome.getErrorCode(), () -> "errorCode=" + outcome.getErrorCode());
    }

    /** 短文档坏页 2 张：超出绝对数门槛，仍按占比判失败（{@code shortDocumentWithTwoBadPagesShouldFail}）。 */
    @Test
    void shortDocumentWithTwoBadPagesShouldFail() {
        PageMetric secondBad = garbledPage();
        secondBad.setPage(2);
        ParsePipeline pipeline = pipeline(fakeParser(
                List.of(garbledPage(), secondBad, normalPage(3)), List.of()));

        ParseOutcome outcome = pipeline.run(context("application/pdf"));

        assertEquals(PipelineTaskStatus.FAILED, outcome.getSuggestedStatus());
        assertEquals(PipelineTaskErrorCode.RATIO_BELOW_THRESHOLD.name(), outcome.getErrorCode());
    }

    /** 单单元且该单元坏：没有可读单元，仍判失败（{@code singleBadUnitDocumentShouldFail}）。 */
    @Test
    void singleBadUnitDocumentShouldFail() {
        ParsePipeline pipeline = pipeline(fakeParser(List.of(garbledPage()), List.of()));

        ParseOutcome outcome = pipeline.run(context("application/pdf"));

        assertEquals(PipelineTaskStatus.FAILED, outcome.getSuggestedStatus());
    }

    /** 短文档规则可关：单元门槛置 0 后回到占比口径（{@code shortDocumentRuleShouldBeConfigurable}）。 */
    @Test
    void shortDocumentRuleShouldBeConfigurable() {
        properties.setShortDocumentMaxUnits(0);
        ParsePipeline pipeline = pipeline(fakeParser(
                List.of(garbledPage(), normalPage(2), normalPage(3)), List.of()));

        ParseOutcome outcome = pipeline.run(context("application/pdf"));

        assertEquals(PipelineTaskStatus.FAILED, outcome.getSuggestedStatus());
    }

    /** 坏页比例达门槛：加一条维度汇总告警；干净文档不加（{@code badPageRatioShouldAddDimensionSummaryWarning}）。 */
    @Test
    void badPageRatioShouldAddDimensionSummaryWarning() {
        List<PageMetric> metrics = new java.util.ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            PageMetric bad = garbledPage();
            bad.setPage(i);
            metrics.add(bad);
        }
        for (int i = 5; i <= 10; i++) {
            metrics.add(normalPage(i));
        }
        ParsePipeline pipeline = pipeline(fakeParser(metrics, List.of()));

        ParseOutcome outcome = pipeline.run(context("application/pdf"));

        List<String> codes = outcome.getParseResult().getQuality().getWarnings().stream()
                .map(w -> w.getCode()).toList();
        assertTrue(codes.contains("QUALITY_BAD_PAGE_RATIO"), () -> "codes=" + codes);
        String summary = outcome.getParseResult().getQuality().getWarnings().stream()
                .filter(w -> "QUALITY_BAD_PAGE_RATIO".equals(w.getCode())).findFirst().orElseThrow().getMessage();
        assertTrue(summary.contains("GARBLED_PAGE 4"), () -> "summary=" + summary);
    }

    /**
     * 守护 {@code cleanDocumentShouldNotAddDimensionSummaryWarning}：该场景行为改动即失败。
     */
    @Test
    void cleanDocumentShouldNotAddDimensionSummaryWarning() {
        ParsePipeline pipeline = pipeline(fakeParser(
                List.of(normalPage(1), normalPage(2)), List.of()));

        ParseOutcome outcome = pipeline.run(context("application/pdf"));

        assertTrue(outcome.getParseResult().getQuality().getWarnings().isEmpty(),
                () -> "warnings=" + outcome.getParseResult().getQuality().getWarnings());
    }

    /**
     * 守护 {@code tableFactShouldWarnWithoutFailing}：该场景行为改动即失败。
     */
    @Test
    void tableFactShouldWarnWithoutFailing() {
        ParseFact fact = new ParseFact();
        fact.setType(SignalType.TABLE);
        fact.setRegion("page 1");
        fact.setEvidence("列对齐聚类失败（疑似无边框/复杂表格）");
        ParsePipeline pipeline = pipeline(fakeParser(List.of(normalPage(1)), List.of(fact)));

        ParseOutcome outcome = pipeline.run(context("application/pdf"));

        assertEquals(PipelineTaskStatus.SUCCESS, outcome.getSuggestedStatus());
        assertEquals(0, outcome.getFailedUnits());
        assertEquals(1, outcome.getParseResult().getQuality().getWarnings().size());
        assertEquals("TABLE_RULE_FALLBACK", outcome.getParseResult().getQuality().getWarnings().getFirst().getCode());
    }

    /**
     * 守护 {@code layoutFactShouldWarnWithoutFailing}：该场景行为改动即失败。
     */
    @Test
    void layoutFactShouldWarnWithoutFailing() {
        ParseFact fact = new ParseFact();
        fact.setType(SignalType.LAYOUT);
        fact.setRegion("page 1");
        fact.setEvidence("版面规则失败");
        ParsePipeline pipeline = pipeline(fakeParser(List.of(normalPage(1)), List.of(fact)));

        ParseOutcome outcome = pipeline.run(context("application/pdf"));

        assertEquals(PipelineTaskStatus.SUCCESS, outcome.getSuggestedStatus());
        assertEquals(0, outcome.getFailedUnits());
        assertEquals("LAYOUT_RULE_FALLBACK", outcome.getParseResult().getQuality().getWarnings().getFirst().getCode());
    }

    /**
     * 守护 {@code embeddedImageFactShouldWarnWithoutFailing}：该场景行为改动即失败。
     */
    @Test
    void embeddedImageFactShouldWarnWithoutFailing() {
        ParseFact fact = new ParseFact();
        fact.setType(SignalType.OCR_IMAGE);
        fact.setRegion("图 1");
        fact.setEvidence("-");
        ParsePipeline pipeline = pipeline(fakeParser(List.of(normalPage(1)), List.of(fact)));

        ParseOutcome outcome = pipeline.run(context("application/pdf"));

        assertEquals(PipelineTaskStatus.SUCCESS, outcome.getSuggestedStatus());
        assertEquals(0, outcome.getFailedUnits());
        assertEquals("IMAGE_TEXT_UNRECOGNIZED", outcome.getParseResult().getQuality().getWarnings().getFirst().getCode());
    }

    /**
     * 守护 {@code factWithoutTypeShouldBeIgnored}：该场景行为改动即失败。
     */
    @Test
    void factWithoutTypeShouldBeIgnored() {
        ParseFact fact = new ParseFact();
        fact.setRegion("page 1");
        fact.setEvidence("未标注类型的事实");
        ParsePipeline pipeline = pipeline(fakeParser(List.of(normalPage(1)), List.of(fact)));

        ParseOutcome outcome = pipeline.run(context("application/pdf"));

        assertEquals(PipelineTaskStatus.SUCCESS, outcome.getSuggestedStatus());
        assertEquals(0, outcome.getParseResult().getQuality().getWarnings().size());
    }

    /**
     * 守护 {@code garbledPageShouldCountFailedUnit}：该场景行为改动即失败。
     */
    @Test
    void garbledPageShouldCountFailedUnit() {
        List<PageMetric> metrics = new java.util.ArrayList<>();
        metrics.add(garbledPage());
        for (int i = 2; i <= 10; i++) {
            metrics.add(normalPage(i));
        }
        ParsePipeline pipeline = pipeline(fakeParser(metrics, List.of()));

        ParseOutcome outcome = pipeline.run(context("application/pdf"));

        assertEquals(PipelineTaskStatus.PARTIAL_SUCCESS, outcome.getSuggestedStatus());
        assertEquals(1, outcome.getFailedUnits());
        assertEquals(1, outcome.getParseResult().getQuality().getFailedPages().size());
        assertEquals("GARBLED_PAGE", outcome.getParseResult().getQuality().getWarnings().getFirst().getCode());
    }

    /** CID 映射失败页：按映射失败口径出告警，仍计失败单元（{@code cidMappingFailedPageShouldWarnWithOwnCode}）。 */
    @Test
    void cidMappingFailedPageShouldWarnWithOwnCode() {
        List<PageMetric> metrics = new java.util.ArrayList<>();
        metrics.add(cidMappingFailedPage());
        for (int i = 2; i <= 10; i++) {
            metrics.add(normalPage(i));
        }
        ParsePipeline pipeline = pipeline(fakeParser(metrics, List.of()));

        ParseOutcome outcome = pipeline.run(context("application/pdf"));

        assertEquals(PipelineTaskStatus.PARTIAL_SUCCESS, outcome.getSuggestedStatus());
        assertEquals(1, outcome.getFailedUnits());
        assertEquals("CID_MAPPING_FAILED_PAGE",
                outcome.getParseResult().getQuality().getWarnings().getFirst().getCode());
    }

    /** 纯矢量页：无文本无图片但有矢量覆盖 → 单独告警、不计失败、与空白页同样从分母剔除（{@code vectorOnlyPageShouldWarnWithoutFailing}）。 */
    @Test
    void vectorOnlyPageShouldWarnWithoutFailing() {
        PageMetric vectorPage = new PageMetric();
        vectorPage.setPage(1);
        vectorPage.setCharCount(0);
        vectorPage.setVectorAreaRatio(0.4);
        ParsePipeline pipeline = pipeline(fakeParser(
                List.of(vectorPage, normalPage(2), normalPage(3)), List.of()));

        ParseOutcome outcome = pipeline.run(context("application/pdf"));

        assertEquals(PipelineTaskStatus.SUCCESS, outcome.getSuggestedStatus());
        assertEquals(0, outcome.getFailedUnits());
        assertEquals(1, outcome.getParseResult().getQuality().getWarnings().size());
        assertEquals("VECTOR_PAGE_SUSPECTED",
                outcome.getParseResult().getQuality().getWarnings().getFirst().getCode());
        assertEquals(1, outcome.getParseResult().getQuality().getBlankPages().size());
    }

    /** 能力快照回填：本路用到的模型名与版本写进快照对应项，未用到的类别保持 null（{@code capabilityUsageShouldBeFilledIntoSnapshot}）。 */
    @Test
    void capabilityUsageShouldBeFilledIntoSnapshot() {
        ParsePipeline pipeline = pipeline(fakeParser(List.of(normalPage(1)), List.of(),
                List.of(CapabilityUsage.table("stub-table", "9.9"))));

        ParseOutcome outcome = pipeline.run(context("application/pdf"));

        CapabilitySnapshot snapshot = outcome.getParseResult().getCapabilitySnapshot();
        assertEquals("stub-table", snapshot.getTable().getModel());
        assertEquals("9.9", snapshot.getTable().getVersion());
        assertNull(snapshot.getOcr());
        assertNull(snapshot.getLayout());
        assertEquals("fake", snapshot.getParserName());
    }

    /**
     * 守护 {@code suspectedImagePageShouldWarnWithoutFailing}：该场景行为改动即失败。
     */
    @Test
    void suspectedImagePageShouldWarnWithoutFailing() {
        // 有文字（chars 充足）但文字占比低：疑似图片页 → 仅告警，不计失败单元
        PageMetric sparse = new PageMetric();
        sparse.setPage(1);
        sparse.setCharCount(300);
        sparse.setGarbledRatio(0.01);
        sparse.setTextAreaRatio(0.02);
        ParsePipeline pipeline = pipeline(fakeParser(List.of(sparse), List.of()));

        ParseOutcome outcome = pipeline.run(context("application/pdf"));

        assertEquals(PipelineTaskStatus.SUCCESS, outcome.getSuggestedStatus());
        assertEquals(0, outcome.getFailedUnits());
        assertEquals(1, outcome.getParseResult().getQuality().getWarnings().size());
        assertEquals("IMAGE_PAGE_SUSPECTED", outcome.getParseResult().getQuality().getWarnings().getFirst().getCode());
    }

    /**
     * 守护 {@code blankPageShouldNotCountAsFailure}：该场景行为改动即失败。
     */
    @Test
    void blankPageShouldNotCountAsFailure() {
        List<PageMetric> metrics = new java.util.ArrayList<>();
        metrics.add(blankPage(1));
        for (int i = 2; i <= 10; i++) {
            metrics.add(normalPage(i));
        }
        ParsePipeline pipeline = pipeline(fakeParser(metrics, List.of()));

        ParseOutcome outcome = pipeline.run(context("application/pdf"));

        assertEquals(PipelineTaskStatus.SUCCESS, outcome.getSuggestedStatus());
        assertEquals(0, outcome.getFailedUnits());
        assertEquals(1, outcome.getParseResult().getQuality().getBlankPages().size());
        assertEquals("BLANK_PAGE", outcome.getParseResult().getQuality().getWarnings().getFirst().getCode());
    }

    /**
     * 守护 {@code allBlankShouldFailWithoutContent}：该场景行为改动即失败。
     */
    @Test
    void allBlankShouldFailWithoutContent() {
        ParsePipeline pipeline = pipeline(fakeParser(
                List.of(blankPage(1), blankPage(2)), List.of()));

        ParseOutcome outcome = pipeline.run(context("application/pdf"));

        assertEquals(PipelineTaskStatus.FAILED, outcome.getSuggestedStatus());
        assertEquals(PipelineTaskErrorCode.PARSE_CORRUPTED.name(), outcome.getErrorCode());
    }

    /**
     * 守护 {@code blankPlusScannedShouldFailScannedUnsupported}：该场景行为改动即失败。
     */
    @Test
    void blankPlusScannedShouldFailScannedUnsupported() {
        ParsePipeline pipeline = pipeline(fakeParser(
                List.of(blankPage(1), scannedPage(2), scannedPage(3)), List.of()));

        ParseOutcome outcome = pipeline.run(context("application/pdf"));

        assertEquals(PipelineTaskStatus.FAILED, outcome.getSuggestedStatus());
        assertEquals(PipelineTaskErrorCode.SCANNED_UNSUPPORTED.name(), outcome.getErrorCode());
    }

    /**
     * 守护 {@code sparseTextPageShouldWarnWithoutFailing}：该场景行为改动即失败。
     */
    @Test
    void sparseTextPageShouldWarnWithoutFailing() {
        // 有文本但极少：只告警，不再当扫描页计失败
        ParsePipeline pipeline = pipeline(fakeParser(List.of(sparseTextPage(1)), List.of()));

        ParseOutcome outcome = pipeline.run(context("application/pdf"));

        assertEquals(PipelineTaskStatus.SUCCESS, outcome.getSuggestedStatus());
        assertEquals(0, outcome.getFailedUnits());
        assertEquals("IMAGE_PAGE_SUSPECTED", outcome.getParseResult().getQuality().getWarnings().getFirst().getCode());
    }

    /**
     * 守护 {@code officeImageOnlyUnitShouldFailScannedUnsupported}：该场景行为改动即失败。
     */
    @Test
    void officeImageOnlyUnitShouldFailScannedUnsupported() {
        // Office 单元无文本但有图片：与扫描页同口径，计失败单元（OCR 未接入）
        ParseOutcome outcome = pipeline(fakeParser(List.of(imageOnlyUnit(1)), List.of()))
                .run(context("application/pdf"));

        assertEquals(PipelineTaskStatus.FAILED, outcome.getSuggestedStatus());
        assertEquals(PipelineTaskErrorCode.SCANNED_UNSUPPORTED.name(), outcome.getErrorCode());
    }

    /**
     * 守护 {@code emptyUnitAmongSheetsShouldKeepGateEffective}：该场景行为改动即失败。
     */
    @Test
    void emptyUnitAmongSheetsShouldKeepGateEffective() {
        // 3 个单元：1 个空 sheet + 2 个有内容 → 空白单元从分母剔除，其余正常判成功
        List<PageMetric> metrics = List.of(blankPage(1), normalPage(2), normalPage(3));

        ParseOutcome outcome = pipeline(fakeParser(metrics, List.of())).run(context("application/pdf"));

        assertEquals(PipelineTaskStatus.SUCCESS, outcome.getSuggestedStatus());
        assertEquals(0, outcome.getFailedUnits());
        assertEquals(1, outcome.getParseResult().getQuality().getBlankPages().size());
    }

    /**
     * 守护 {@code noParserShouldFail}：该场景行为改动即失败。
     */
    @Test
    void noParserShouldFail() {
        ParsePipeline pipeline = pipeline(fakeParser(List.of(), List.of()));

        ParseOutcome outcome = pipeline.run(context("application/msword"));

        assertEquals(PipelineTaskStatus.FAILED, outcome.getSuggestedStatus());
        assertEquals(PipelineTaskErrorCode.PARSE_FAILED.name(), outcome.getErrorCode());
    }
}
