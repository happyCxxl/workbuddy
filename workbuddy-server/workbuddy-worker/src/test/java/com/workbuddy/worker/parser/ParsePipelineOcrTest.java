package com.workbuddy.worker.parser;

import com.workbuddy.common.domain.parse.ParseOutcome;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.domain.parse.capability.OcrLine;
import com.workbuddy.common.domain.parse.capability.StructuredOcrResult;
import com.workbuddy.common.domain.parse.signal.PageMetric;
import com.workbuddy.worker.parser.capability.CapabilityRegistry;
import com.workbuddy.worker.parser.capability.LayoutProvider;
import com.workbuddy.worker.parser.capability.StructuredOcrProvider;
import com.workbuddy.worker.parser.capability.TableStructureProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

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
        ParsePipeline pipeline = fixture.pipeline(fixture.fakeParser(List.of(scanned), List.of()));

        ParseOutcome outcome = pipeline.run(fixture.context("application/pdf"));

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
}
