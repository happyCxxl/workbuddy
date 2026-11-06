package com.workbuddy.worker.parser.pdf.pipeline;

import com.workbuddy.worker.parser.ParseContext;
import com.workbuddy.worker.parser.pdf.detect.HeaderFooterDetector;
import com.workbuddy.worker.parser.pdf.detect.HeaderFooterKeys;
import com.workbuddy.worker.parser.pdf.model.PageLines;

import java.util.List;
import java.util.Set;

/**
 * 跨页页眉/页脚识别步骤：基于全档行摘要识别页眉文本键与页脚键。
 */
public final class HeaderFooterStep implements DocumentStep<HeaderFooterStep.Input, HeaderFooterKeys> {

    /**
     * 页眉/页脚识别输入。
     *
     * @param pages 全档行摘要
     * @param context 解析上下文
     */
    public record Input(List<PageLines> pages, ParseContext context) {
    }

    /**
     * 执行页眉/页脚识别。
     *
     * @param input 识别输入
     * @return 页眉文本键与页脚键
     */
    @Override
    public HeaderFooterKeys execute(Input input) {
        Set<String> headers = HeaderFooterDetector.detectHeaders(input.pages(), input.context());
        HeaderFooterDetector.FooterKeys footers = HeaderFooterDetector.detectFooters(
                input.pages(), input.context());
        return new HeaderFooterKeys(headers, footers);
    }
}
