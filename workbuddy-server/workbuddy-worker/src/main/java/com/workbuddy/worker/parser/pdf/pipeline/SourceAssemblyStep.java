package com.workbuddy.worker.parser.pdf.pipeline;

import com.workbuddy.common.domain.parse.ParseSource;

/**
 * 结果组装步骤：只把提取结果与装配结果组装成 {@link ParseSource}。
 */
public final class SourceAssemblyStep
        implements DocumentStep<SourceAssemblyStep.Input, ParseSource> {

    private final String provider;

    /**
     * 构造结果组装步骤。
     *
     * @param provider 解析提供方标识（能力名 + 版本）
     */
    public SourceAssemblyStep(String provider) {
        this.provider = provider;
    }

    /**
     * 结果组装输入。
     *
     * @param extracted 逐页提取结果
     * @param assembled 页面装配结果
     */
    public record Input(ExtractedDocument extracted, PageAssemblyResult assembled) {
    }

    /**
     * 组装解析结果。
     *
     * @param input 组装输入
     * @return 原生解析路结果
     */
    @Override
    public ParseSource execute(Input input) {
        ParseSource source = ParseSource.nativeSource(provider);
        source.setUnitCount(input.extracted().pageCount());
        source.setPageMetrics(input.extracted().pageMetrics());
        source.setPageDimensions(input.extracted().pageDimensions());
        source.setElements(input.assembled().elements());
        source.setFacts(input.assembled().facts());
        source.setCapabilities(input.assembled().capabilities());
        return source;
    }
}
