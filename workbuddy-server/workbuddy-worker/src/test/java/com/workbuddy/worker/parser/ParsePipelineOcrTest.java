package com.workbuddy.worker.parser;

import com.workbuddy.common.domain.parse.ParseOutcome;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.domain.parse.ParseSummary;
import com.workbuddy.common.domain.parse.capability.OcrLine;
import com.workbuddy.common.domain.parse.capability.StructuredOcrResult;
import com.workbuddy.common.domain.parse.signal.PageMetric;
import com.workbuddy.common.enums.task.PipelineTaskErrorCode;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.worker.parser.capability.CapabilityRegistry;
import com.workbuddy.worker.parser.capability.LayoutProvider;
import com.workbuddy.worker.parser.capability.StructuredOcrProvider;
import com.workbuddy.worker.parser.capability.TableStructureProvider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 解析管线 OCR 路由单测：扫描页交能力识别、未接入能力保持预留占位。
 */
class ParsePipelineOcrTest {

    private final ParsePipelineTestFixture fixture = new ParsePipelineTestFixture();

    /** OCR 路由：整页无文本且含图片的页交能力，识别结果进 ocr 路并回填快照的 ocr 项（{@code scannedPageShouldBeFilledIntoOcrSource}）。 */
    @Test
    void scannedPageShouldBeFilledIntoOcrSource() {
        PageMetric scanned = new PageMetric();
        scanned.setPage(1);
        scanned.setCharCount(0);
        scanned.setImageCount(1);
        ParsePipeline pipeline = fixture.pipeline(fixture.fakeParser(List.of(scanned), List.of()), stubOcrRegistry());

        ParseOutcome outcome = pipeline.run(fixture.context("application/pdf"));

        ParseSource ocr = outcome.getParseResult().getSources().get(1);
        assertEquals("ocr", ocr.getSource());
        assertEquals(2, ocr.getElements().size(), () -> "elements=" + ocr.getElements());
        assertEquals(1, ocr.getElements().getFirst().getPage());
        assertEquals(Boolean.FALSE, ocr.getElements().getFirst().getNeedsOcr());
        assertEquals(0.94, ocr.getElements().getFirst().getConfidence(), 0.0001);
        assertTrue(ocr.getFacts().stream().anyMatch(fact -> fact.getEvidence().contains("文字由能力补出")),
                () -> "facts=" + ocr.getFacts());
        assertEquals("stub-ocr", ocr.getCapabilities().getFirst().getModel());
        assertEquals("stub-ocr", outcome.getParseResult().getCapabilitySnapshot().getOcr().getModel());
        assertEquals(PipelineTaskStatus.PARTIAL_SUCCESS, outcome.getSuggestedStatus());
        assertTrue(outcome.getParseResult().getQuality().getFailedPages().isEmpty());
        ParseSummary summary = outcome.getParseResult().getSummary();
        assertEquals(1, summary.getOcrPages());
        assertEquals(1, summary.getOcrRecoveredUnits());
        assertEquals(0, summary.getFailedUnits());
    }

    /** 未接入 OCR：ocr 路保持预留占位（无元素、文案不变）（{@code withoutOcrCapabilityShouldKeepPlaceholderSource}）。 */
    @Test
    void withoutOcrCapabilityShouldKeepPlaceholderSource() {
        PageMetric scanned = new PageMetric();
        scanned.setPage(1);
        scanned.setCharCount(0);
        scanned.setImageCount(1);
        ParsePipeline pipeline = fixture.pipeline(fixture.fakeParser(List.of(scanned), List.of()));

        ParseOutcome outcome = pipeline.run(fixture.context("application/pdf"));

        ParseSource ocr = outcome.getParseResult().getSources().get(1);
        assertEquals("ocr", ocr.getSource());
        assertTrue(ocr.getElements().isEmpty());
        assertTrue(ocr.getNote().contains("能力未接入"), () -> "note=" + ocr.getNote());
        assertNull(outcome.getParseResult().getCapabilitySnapshot().getOcr());
        assertEquals(PipelineTaskStatus.FAILED, outcome.getSuggestedStatus());
        assertEquals(PipelineTaskErrorCode.SCANNED_UNSUPPORTED.name(), outcome.getErrorCode());
    }

    /** 部分扫描页被 OCR 覆盖：只把未覆盖页计入失败（{@code partiallyRecoveredScannedPagesShouldLeaveOnlyUncoveredAsFailed}）。 */
    @Test
    void partiallyRecoveredScannedPagesShouldLeaveOnlyUncoveredAsFailed() {
        List<PageMetric> metrics = new ArrayList<>();
        metrics.add(fixture.scannedPage(1));
        metrics.add(fixture.scannedPage(2));
        for (int i = 3; i <= 10; i++) {
            metrics.add(fixture.normalPage(i));
        }
        ParsePipeline pipeline = fixture.pipeline(fixture.fakeParser(metrics, List.of()),
                stubOcrRegistry(page -> page == 1));

        ParseOutcome outcome = pipeline.run(fixture.context("application/pdf"));

        assertEquals(PipelineTaskStatus.PARTIAL_SUCCESS, outcome.getSuggestedStatus());
        assertEquals(1, outcome.getFailedUnits());
        assertEquals(List.of(2), outcome.getParseResult().getQuality().getFailedPages());
    }

    /**
     * 乱码页也交 OCR 且恢复后不计失败单元（{@code garbledPageShouldBeRecoveredByOcr}）。
     */
    @Test
    void garbledPageShouldBeRecoveredByOcr() {
        fixture.properties().setGarbledRateThreshold(0.2);
        PageMetric garbled = new PageMetric();
        garbled.setPage(1);
        garbled.setCharCount(200);
        garbled.setGarbledRatio(0.5);
        List<PageMetric> metrics = new ArrayList<>();
        metrics.add(garbled);
        for (int i = 2; i <= 10; i++) {
            metrics.add(fixture.normalPage(i));
        }
        ParsePipeline pipeline = fixture.pipeline(fixture.fakeParser(metrics, List.of()), stubOcrRegistry());

        ParseOutcome outcome = pipeline.run(fixture.context("application/pdf"));

        ParseSource ocr = outcome.getParseResult().getSources().get(1);
        assertEquals(2, ocr.getElements().size());
        assertEquals(1, ocr.getElements().getFirst().getPage());
        assertEquals(PipelineTaskStatus.SUCCESS, outcome.getSuggestedStatus());
        assertEquals(0, outcome.getFailedUnits());
        assertTrue(outcome.getParseResult().getQuality().getFailedPages().isEmpty());
    }

    /** 只接 OCR 能力的注册表：桩为每页返回两行（页码随区域走） */
    private CapabilityRegistry stubOcrRegistry() {
        return stubOcrRegistry(page -> true);
    }

    /** 只接 OCR 能力的注册表：仅对 predicate 命中的页返回识别行，其余页返回 null。 */
    private CapabilityRegistry stubOcrRegistry(Predicate<Integer> covered) {
        return new CapabilityRegistry() {
            @Override
            public StructuredOcrProvider ocr() {
                return (region, ctx) -> {
                    if (!covered.test(region.getPage())) {
                        return null;
                    }
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
}
