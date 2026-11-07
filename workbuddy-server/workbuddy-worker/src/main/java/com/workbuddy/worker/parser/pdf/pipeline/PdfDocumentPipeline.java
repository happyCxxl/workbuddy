package com.workbuddy.worker.parser.pdf.pipeline;

import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.worker.parser.ParseContext;
import com.workbuddy.worker.parser.pdf.detect.HeaderFooterKeys;

import java.io.IOException;

/**
 * PDF 文档流水线：按固定顺序执行加载、逐页提取、跨页页眉/页脚、逐页装配与结果组装。
 *
 * <p>顺序写在字段与 {@link #run(ParseContext)} 里，不用运行期排序；资源由 try-with-resources 关闭。
 */
public final class PdfDocumentPipeline {

    private final DocumentLoadStep load;
    private final PageExtractionStep extract;
    private final HeaderFooterStep headerFooter;
    private final PageAssemblyStep assemble;
    private final SourceAssemblyStep source;

    /**
     * 构造文档流水线。
     *
     * @param load 文档加载步骤
     * @param extract 逐页提取步骤
     * @param headerFooter 跨页页眉/页脚步骤
     * @param assemble 逐页装配步骤
     * @param source 结果组装步骤
     */
    public PdfDocumentPipeline(DocumentLoadStep load, PageExtractionStep extract,
                               HeaderFooterStep headerFooter, PageAssemblyStep assemble,
                               SourceAssemblyStep source) {
        this.load = load;
        this.extract = extract;
        this.headerFooter = headerFooter;
        this.assemble = assemble;
        this.source = source;
    }

    /**
     * 执行 PDF 解析。
     *
     * @param context 解析上下文
     * @return 原生解析路结果
     * @throws IOException 加载、提取或装配失败
     */
    public ParseSource run(ParseContext context) throws IOException {
        try (LoadedPdf loaded = load.execute(context)) {
            try (ExtractedDocument extracted = extract.execute(
                    new PageExtractionStep.Input(loaded, context))) {
                HeaderFooterKeys keys = headerFooter.execute(
                        new HeaderFooterStep.Input(extracted.pageLines(), context));
                PageAssemblyResult assembled = assemble.execute(
                        new PageAssemblyStep.Input(extracted, keys, loaded, context));
                return source.execute(new SourceAssemblyStep.Input(extracted, assembled));
            }
        }
    }
}
