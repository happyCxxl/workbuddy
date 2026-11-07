package com.workbuddy.worker.parser.pdf.pipeline;

import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.enums.parse.UnitType;
import com.workbuddy.worker.parser.ParseContext;

import java.io.IOException;

/**
 * PDF 文档流水线：按固定顺序执行加载、逐页提取、逐页装配与结果组装。
 *
 * <p>顺序写在字段与 {@link #run(ParseContext)} 里，不用运行期排序；资源由 try-with-resources 关闭。
 * 产出按单元组织（每页一个单元）：元素、行摘要、单元指标与页尺寸都挂在单元上；
 * 页眉页脚属文档级判定，由组装环节按单元行摘要识别。
 */
public final class PdfDocumentPipeline {

    private final String provider;
    private final DocumentLoadStep load;
    private final PageExtractionStep extract;
    private final PageAssemblyStep assemble;

    /**
     * 构造文档流水线。
     *
     * @param provider 解析提供方标识（能力名 + 版本）
     * @param load 文档加载步骤
     * @param extract 逐页提取步骤
     * @param assemble 逐页装配步骤
     */
    public PdfDocumentPipeline(String provider, DocumentLoadStep load, PageExtractionStep extract,
                               PageAssemblyStep assemble) {
        this.provider = provider;
        this.load = load;
        this.extract = extract;
        this.assemble = assemble;
    }

    /**
     * 执行 PDF 解析。
     *
     * @param context 解析上下文
     * @return 解析结果（单元集合 + 文档级汇总）
     * @throws IOException 加载、提取或装配失败
     */
    public ParseSource run(ParseContext context) throws IOException {
        try (LoadedPdf loaded = load.execute(context)) {
            try (ExtractedDocument extracted = extract.execute(
                    new PageExtractionStep.Input(loaded, context))) {
                ParseSource source = ParseSource.nativeSource(provider);
                source.setUnitType(UnitType.PAGE);
                source.setUnits(extracted.units());
                source.summarizeUnits();
                assemble.execute(new PageAssemblyStep.Input(extracted, loaded, context, source));
                return source;
            }
        }
    }
}
