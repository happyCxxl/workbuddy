package com.workbuddy.worker.parser;

import com.workbuddy.common.domain.parse.ParseOutcome;
import com.workbuddy.common.domain.parse.signal.PageMetric;
import com.workbuddy.common.enums.task.PipelineTaskErrorCode;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 解析管线门槛与状态单测：成功/部分成功/失败三档、短文档绝对数规则、空白/扫描/乱码页与无可用解析器。
 */
class ParsePipelineGateTest {

    private final ParsePipelineTestFixture fixture = new ParsePipelineTestFixture();

    /**
     * 守护 {@code allPagesOkShouldSuggestSuccess}：该场景行为改动即失败。
     */
    @Test
    void allPagesOkShouldSuggestSuccess() {
        ParsePipeline pipeline = fixture.pipeline(fixture.fakeParser(
                List.of(fixture.normalPage(1), fixture.normalPage(2)), List.of()));

        ParseOutcome outcome = pipeline.run(fixture.context("application/pdf"));

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
        List<PageMetric> metrics = new ArrayList<>();
        metrics.add(fixture.scannedPage(1));
        for (int i = 2; i <= 10; i++) {
            metrics.add(fixture.normalPage(i));
        }
        ParsePipeline pipeline = fixture.pipeline(fixture.fakeParser(metrics, List.of()));

        ParseOutcome outcome = pipeline.run(fixture.context("application/pdf"));

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
        ParsePipeline pipeline = fixture.pipeline(fixture.fakeParser(
                List.of(fixture.scannedPage(1), fixture.scannedPage(2), fixture.scannedPage(3)), List.of()));

        ParseOutcome outcome = pipeline.run(fixture.context("application/pdf"));

        assertEquals(PipelineTaskStatus.FAILED, outcome.getSuggestedStatus());
        assertEquals(PipelineTaskErrorCode.SCANNED_UNSUPPORTED.name(), outcome.getErrorCode());
    }

    /**
     * 守护 {@code ratioBelowThresholdShouldFail}：该场景行为改动即失败。
     */
    @Test
    void ratioBelowThresholdShouldFail() {
        List<PageMetric> metrics = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            metrics.add(fixture.scannedPage(i));
        }
        for (int i = 6; i <= 10; i++) {
            metrics.add(fixture.normalPage(i));
        }
        ParsePipeline pipeline = fixture.pipeline(fixture.fakeParser(metrics, List.of()));

        ParseOutcome outcome = pipeline.run(fixture.context("application/pdf"));

        assertEquals(PipelineTaskStatus.FAILED, outcome.getSuggestedStatus());
        assertEquals(PipelineTaskErrorCode.RATIO_BELOW_THRESHOLD.name(), outcome.getErrorCode());
    }

    /**
     * 短文档（≤3 单元）坏页 1 张：按坏页绝对数放行，不落 RATIO_BELOW_THRESHOLD
     * （{@code shortDocumentWithOneBadPageShouldBePartialSuccess}）。
     */
    @Test
    void shortDocumentWithOneBadPageShouldBePartialSuccess() {
        ParsePipeline pipeline = fixture.pipeline(fixture.fakeParser(
                List.of(fixture.garbledPage(), fixture.normalPage(2), fixture.normalPage(3)), List.of()));

        ParseOutcome outcome = pipeline.run(fixture.context("application/pdf"));

        assertEquals(PipelineTaskStatus.PARTIAL_SUCCESS, outcome.getSuggestedStatus());
        assertEquals(1, outcome.getFailedUnits());
        assertNull(outcome.getErrorCode(), () -> "errorCode=" + outcome.getErrorCode());
    }

    /** 短文档坏页 2 张：超出绝对数门槛，仍按占比判失败（{@code shortDocumentWithTwoBadPagesShouldFail}）。 */
    @Test
    void shortDocumentWithTwoBadPagesShouldFail() {
        PageMetric secondBad = fixture.garbledPage();
        secondBad.setPage(2);
        ParsePipeline pipeline = fixture.pipeline(fixture.fakeParser(
                List.of(fixture.garbledPage(), secondBad, fixture.normalPage(3)), List.of()));

        ParseOutcome outcome = pipeline.run(fixture.context("application/pdf"));

        assertEquals(PipelineTaskStatus.FAILED, outcome.getSuggestedStatus());
        assertEquals(PipelineTaskErrorCode.RATIO_BELOW_THRESHOLD.name(), outcome.getErrorCode());
    }

    /** 单单元且该单元坏：没有可读单元，仍判失败（{@code singleBadUnitDocumentShouldFail}）。 */
    @Test
    void singleBadUnitDocumentShouldFail() {
        ParsePipeline pipeline = fixture.pipeline(fixture.fakeParser(List.of(fixture.garbledPage()), List.of()));

        ParseOutcome outcome = pipeline.run(fixture.context("application/pdf"));

        assertEquals(PipelineTaskStatus.FAILED, outcome.getSuggestedStatus());
    }

    /** 短文档规则可关：单元门槛置 0 后回到占比口径（{@code shortDocumentRuleShouldBeConfigurable}）。 */
    @Test
    void shortDocumentRuleShouldBeConfigurable() {
        fixture.properties().setShortDocumentMaxUnits(0);
        ParsePipeline pipeline = fixture.pipeline(fixture.fakeParser(
                List.of(fixture.garbledPage(), fixture.normalPage(2), fixture.normalPage(3)), List.of()));

        ParseOutcome outcome = pipeline.run(fixture.context("application/pdf"));

        assertEquals(PipelineTaskStatus.FAILED, outcome.getSuggestedStatus());
    }

    /** 坏页比例达门槛：加一条维度汇总告警；干净文档不加（{@code badPageRatioShouldAddDimensionSummaryWarning}）。 */
    @Test
    void badPageRatioShouldAddDimensionSummaryWarning() {
        List<PageMetric> metrics = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            PageMetric bad = fixture.garbledPage();
            bad.setPage(i);
            metrics.add(bad);
        }
        for (int i = 5; i <= 10; i++) {
            metrics.add(fixture.normalPage(i));
        }
        ParsePipeline pipeline = fixture.pipeline(fixture.fakeParser(metrics, List.of()));

        ParseOutcome outcome = pipeline.run(fixture.context("application/pdf"));

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
        ParsePipeline pipeline = fixture.pipeline(fixture.fakeParser(
                List.of(fixture.normalPage(1), fixture.normalPage(2)), List.of()));

        ParseOutcome outcome = pipeline.run(fixture.context("application/pdf"));

        assertTrue(outcome.getParseResult().getQuality().getWarnings().isEmpty(),
                () -> "warnings=" + outcome.getParseResult().getQuality().getWarnings());
    }

    /**
     * 守护 {@code garbledPageShouldCountFailedUnit}：该场景行为改动即失败。
     */
    @Test
    void garbledPageShouldCountFailedUnit() {
        List<PageMetric> metrics = new ArrayList<>();
        metrics.add(fixture.garbledPage());
        for (int i = 2; i <= 10; i++) {
            metrics.add(fixture.normalPage(i));
        }
        ParsePipeline pipeline = fixture.pipeline(fixture.fakeParser(metrics, List.of()));

        ParseOutcome outcome = pipeline.run(fixture.context("application/pdf"));

        assertEquals(PipelineTaskStatus.PARTIAL_SUCCESS, outcome.getSuggestedStatus());
        assertEquals(1, outcome.getFailedUnits());
        assertEquals(1, outcome.getParseResult().getQuality().getFailedPages().size());
        assertEquals("GARBLED_PAGE", outcome.getParseResult().getQuality().getWarnings().getFirst().getCode());
    }

    /** CID 映射失败页：按映射失败口径出告警，仍计失败单元（{@code cidMappingFailedPageShouldWarnWithOwnCode}）。 */
    @Test
    void cidMappingFailedPageShouldWarnWithOwnCode() {
        List<PageMetric> metrics = new ArrayList<>();
        metrics.add(fixture.cidMappingFailedPage());
        for (int i = 2; i <= 10; i++) {
            metrics.add(fixture.normalPage(i));
        }
        ParsePipeline pipeline = fixture.pipeline(fixture.fakeParser(metrics, List.of()));

        ParseOutcome outcome = pipeline.run(fixture.context("application/pdf"));

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
        ParsePipeline pipeline = fixture.pipeline(fixture.fakeParser(
                List.of(vectorPage, fixture.normalPage(2), fixture.normalPage(3)), List.of()));

        ParseOutcome outcome = pipeline.run(fixture.context("application/pdf"));

        assertEquals(PipelineTaskStatus.SUCCESS, outcome.getSuggestedStatus());
        assertEquals(0, outcome.getFailedUnits());
        assertEquals(1, outcome.getParseResult().getQuality().getWarnings().size());
        assertEquals("VECTOR_PAGE_SUSPECTED",
                outcome.getParseResult().getQuality().getWarnings().getFirst().getCode());
        assertEquals(1, outcome.getParseResult().getQuality().getBlankPages().size());
    }

    /**
     * 守护 {@code suspectedImagePageShouldWarnWithoutFailing}：有文字但文字占比低且有图片覆盖 → 仅告警，不计失败单元。
     */
    @Test
    void suspectedImagePageShouldWarnWithoutFailing() {
        PageMetric sparse = new PageMetric();
        sparse.setPage(1);
        sparse.setCharCount(300);
        sparse.setGarbledRatio(0.01);
        sparse.setTextAreaRatio(0.02);
        // P2-9：PDF 图片页必须同时有图片覆盖，不能只凭文字占比低
        sparse.setImageAreaRatio(0.5);
        ParsePipeline pipeline = fixture.pipeline(fixture.fakeParser(List.of(sparse), List.of()));

        ParseOutcome outcome = pipeline.run(fixture.context("application/pdf"));

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
        List<PageMetric> metrics = new ArrayList<>();
        metrics.add(fixture.blankPage(1));
        for (int i = 2; i <= 10; i++) {
            metrics.add(fixture.normalPage(i));
        }
        ParsePipeline pipeline = fixture.pipeline(fixture.fakeParser(metrics, List.of()));

        ParseOutcome outcome = pipeline.run(fixture.context("application/pdf"));

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
        ParsePipeline pipeline = fixture.pipeline(fixture.fakeParser(
                List.of(fixture.blankPage(1), fixture.blankPage(2)), List.of()));

        ParseOutcome outcome = pipeline.run(fixture.context("application/pdf"));

        assertEquals(PipelineTaskStatus.FAILED, outcome.getSuggestedStatus());
        assertEquals(PipelineTaskErrorCode.PARSE_CORRUPTED.name(), outcome.getErrorCode());
    }

    /**
     * 守护 {@code blankPlusScannedShouldFailScannedUnsupported}：该场景行为改动即失败。
     */
    @Test
    void blankPlusScannedShouldFailScannedUnsupported() {
        ParsePipeline pipeline = fixture.pipeline(fixture.fakeParser(
                List.of(fixture.blankPage(1), fixture.scannedPage(2), fixture.scannedPage(3)), List.of()));

        ParseOutcome outcome = pipeline.run(fixture.context("application/pdf"));

        assertEquals(PipelineTaskStatus.FAILED, outcome.getSuggestedStatus());
        assertEquals(PipelineTaskErrorCode.SCANNED_UNSUPPORTED.name(), outcome.getErrorCode());
    }

    /**
     * 守护 {@code sparseTextPageShouldWarnWithoutFailing}：该场景行为改动即失败。
     */
    @Test
    void sparseTextPageShouldWarnWithoutFailing() {
        // 有文本但极少：只告警，不再当扫描页计失败
        ParsePipeline pipeline = fixture.pipeline(fixture.fakeParser(List.of(fixture.sparseTextPage(1)), List.of()));

        ParseOutcome outcome = pipeline.run(fixture.context("application/pdf"));

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
        ParseOutcome outcome = fixture.pipeline(fixture.fakeParser(List.of(fixture.imageOnlyUnit(1)), List.of()))
                .run(fixture.context("application/pdf"));

        assertEquals(PipelineTaskStatus.FAILED, outcome.getSuggestedStatus());
        assertEquals(PipelineTaskErrorCode.SCANNED_UNSUPPORTED.name(), outcome.getErrorCode());
    }

    /**
     * 守护 {@code emptyUnitAmongSheetsShouldKeepGateEffective}：该场景行为改动即失败。
     */
    @Test
    void emptyUnitAmongSheetsShouldKeepGateEffective() {
        // 3 个单元：1 个空 sheet + 2 个有内容 → 空白单元从分母剔除，其余正常判成功
        List<PageMetric> metrics = List.of(fixture.blankPage(1), fixture.normalPage(2), fixture.normalPage(3));

        ParseOutcome outcome = fixture.pipeline(fixture.fakeParser(metrics, List.of()))
                .run(fixture.context("application/pdf"));

        assertEquals(PipelineTaskStatus.SUCCESS, outcome.getSuggestedStatus());
        assertEquals(0, outcome.getFailedUnits());
        assertEquals(1, outcome.getParseResult().getQuality().getBlankPages().size());
    }

    /**
     * 守护 {@code noParserShouldFail}：该场景行为改动即失败。
     */
    @Test
    void noParserShouldFail() {
        ParsePipeline pipeline = fixture.pipeline(fixture.fakeParser(List.of(), List.of()));

        ParseOutcome outcome = pipeline.run(fixture.context("application/msword"));

        assertEquals(PipelineTaskStatus.FAILED, outcome.getSuggestedStatus());
        assertEquals(PipelineTaskErrorCode.PARSE_FAILED.name(), outcome.getErrorCode());
    }
}
