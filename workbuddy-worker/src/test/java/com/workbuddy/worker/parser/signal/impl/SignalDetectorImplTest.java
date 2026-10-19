package com.workbuddy.worker.parser.signal.impl;

import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.domain.parse.signal.PageMetric;
import com.workbuddy.common.domain.parse.signal.Signal;
import com.workbuddy.common.enums.parse.SignalSubtype;
import com.workbuddy.worker.parser.ParseContext;
import com.workbuddy.worker.parser.ParseProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 疑似图片单元判定单测：两条口径分开验证 —— PDF 侧看文字占比，Office 侧（无面积口径）看「图片数 + 字符数」；
 * 两条都不成立时不产信号。
 */
class SignalDetectorImplTest {

    private final SignalDetectorImpl detector = new SignalDetectorImpl();

    /** Office 形态：无面积口径、有内嵌图片且字符极少 → 疑似图片单元，证据点名「无面积口径」（{@code officeUnitWithPicturesAndFewCharsShouldBeSuspect}）。 */
    @Test
    void officeUnitWithPicturesAndFewCharsShouldBeSuspect() {
        PageMetric metric = metric(1, 10);
        metric.setImageCount(2);

        List<Signal> signals = detect(metric);

        assertEquals(1, signals.size(), () -> "signals=" + signals);
        assertEquals(SignalSubtype.IMAGE_LOW_RATIO.name(), signals.getFirst().getSubtype());
        assertTrue(signals.getFirst().getThreshold().contains("无面积口径"),
                () -> "threshold=" + signals.getFirst().getThreshold());
    }

    /** PDF 形态：文字占比低（无内嵌图片）也判疑似图片单元，证据带 textArea 实测值（{@code pdfUnitWithLowTextRatioShouldBeSuspect}）。 */
    @Test
    void pdfUnitWithLowTextRatioShouldBeSuspect() {
        PageMetric metric = metric(1, 800);
        metric.setTextAreaRatio(0.02);

        List<Signal> signals = detect(metric);

        assertEquals(1, signals.size(), () -> "signals=" + signals);
        assertEquals(SignalSubtype.IMAGE_LOW_RATIO.name(), signals.getFirst().getSubtype());
        assertTrue(signals.getFirst().getThreshold().contains("textArea=0.020"),
                () -> "threshold=" + signals.getFirst().getThreshold());
    }

    /** 有图片但字符数够多：不判疑似图片单元（Office 口径的门槛在字符数上）（{@code officeUnitWithEnoughCharsShouldNotBeSuspect}）。 */
    @Test
    void officeUnitWithEnoughCharsShouldNotBeSuspect() {
        PageMetric metric = metric(1, 500);
        metric.setImageCount(3);

        assertEquals(List.of(), detect(metric));
    }

    /** 正常页：文字占比高且字符多 → 无信号（{@code normalUnitShouldYieldNoSignal}）。 */
    @Test
    void normalUnitShouldYieldNoSignal() {
        PageMetric metric = metric(1, 1000);
        metric.setTextAreaRatio(0.62);

        assertEquals(List.of(), detect(metric));
    }

    private PageMetric metric(int page, int chars) {
        PageMetric metric = new PageMetric();
        metric.setPage(page);
        metric.setCharCount(chars);
        return metric;
    }

    private List<Signal> detect(PageMetric metric) {
        ParseSource source = ParseSource.nativeSource("fake-1.0");
        source.setUnitCount(1);
        source.setPageMetrics(List.of(metric));
        ParseContext context = new ParseContext();
        context.setProperties(new ParseProperties());
        return detector.detect(source, context);
    }
}
