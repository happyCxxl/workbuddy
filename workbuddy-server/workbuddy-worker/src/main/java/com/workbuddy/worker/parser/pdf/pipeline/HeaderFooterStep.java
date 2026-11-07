package com.workbuddy.worker.parser.pdf.pipeline;

import com.workbuddy.common.domain.parse.ParseUnitResult;
import com.workbuddy.worker.parser.ParseContext;
import com.workbuddy.worker.structure.craft.HeaderFooterDetector;
import com.workbuddy.worker.structure.craft.HeaderFooterDetector.BandRules;
import com.workbuddy.worker.structure.craft.HeaderFooterKeys;

import java.util.List;
import java.util.Set;

/**
 * 跨页页眉/页脚识别步骤：基于单元行摘要识别页眉文本键与页脚键。
 * 判定需要看多页，但输入只是单元数据；本步骤不读解析器句柄。
 */
public final class HeaderFooterStep implements DocumentStep<HeaderFooterStep.Input, HeaderFooterKeys> {

    /**
     * 页眉/页脚识别输入。
     *
     * @param units 单元集合（行摘要已定型）
     * @param context 解析上下文
     */
    public record Input(List<ParseUnitResult> units, ParseContext context) {
    }

    /**
     * 执行页眉/页脚识别。
     *
     * @param input 识别输入
     * @return 页眉文本键与页脚键
     */
    @Override
    public HeaderFooterKeys execute(Input input) {
        BandRules rules = input.context().getProperties().bandRules();
        Set<String> headers = HeaderFooterDetector.detectHeaders(input.units(), rules);
        HeaderFooterDetector.FooterKeys footers = HeaderFooterDetector.detectFooters(input.units(), rules);
        return new HeaderFooterKeys(headers, footers);
    }
}
