package com.workbuddy.worker.parser.pdf;

import com.workbuddy.common.domain.parse.ParseDocument;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.enums.input.FileFormat;
import com.workbuddy.worker.parser.DocumentParserPort;
import com.workbuddy.worker.parser.ParseContext;
import com.workbuddy.worker.parser.capability.CapabilityRegistry;
import com.workbuddy.worker.parser.pdf.extract.PdfPageExtractor;
import com.workbuddy.worker.parser.pdf.layout.PageLayoutAnalyzer;
import com.workbuddy.worker.parser.pdf.pipeline.DocumentLoadStep;
import com.workbuddy.worker.parser.pdf.pipeline.HeaderFooterStep;
import com.workbuddy.worker.parser.pdf.pipeline.PageAssemblyStep;
import com.workbuddy.worker.parser.pdf.pipeline.PageExtractionStep;
import com.workbuddy.worker.parser.pdf.pipeline.PdfDocumentPipeline;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.CancellationException;

/**
 * PDFBox 原生解析器（数字 PDF）：实现文档解析端口，内部委托 {@link PdfDocumentPipeline}。
 *
 * <p>本类只保留 MIME 适配、能力标识与异常边界；加载、提取、跨页识别、装配与结果组装由流水线步骤完成。
 */
@Slf4j
@Component
public class PdfBoxDocumentParser implements DocumentParserPort {

    private static final String CAPABILITY = "pdfbox";
    private static final String CAPABILITY_VERSION = "3.0.4";

    private final PdfDocumentPipeline pipeline;

    /**
     * 只跑规则路径（不接模型能力）的构造器。
     *
     * @param layoutAnalyzer 版面分析器
     */
    public PdfBoxDocumentParser(PageLayoutAnalyzer layoutAnalyzer) {
        this(layoutAnalyzer, null);
    }

    /**
     * 运行期构造器：注入能力注册表（未开启或未接入时注册表返回 null）。
     *
     * @param layoutAnalyzer 版面分析器
     * @param capabilityRegistry 模型能力注册表（可空）
     */
    @Autowired
    public PdfBoxDocumentParser(PageLayoutAnalyzer layoutAnalyzer, CapabilityRegistry capabilityRegistry) {
        this(capabilityRegistry, new PdfPageExtractor(layoutAnalyzer));
    }

    /**
     * 测试构造器：注入页提取器，便于断言每页只提取一次。
     *
     * @param capabilityRegistry 模型能力注册表（可空）
     * @param pageExtractor 页提取器
     */
    PdfBoxDocumentParser(CapabilityRegistry capabilityRegistry, PdfPageExtractor pageExtractor) {
        this.pipeline = new PdfDocumentPipeline(CAPABILITY + "-" + CAPABILITY_VERSION,
                new DocumentLoadStep(),
                new PageExtractionStep(pageExtractor),
                new HeaderFooterStep(),
                new PageAssemblyStep(capabilityRegistry));
    }

    /**
     * 是否支持该媒体类型。
     *
     * @param mimeType 媒体类型
     * @return 支持 PDF 为 true
     */
    @Override
    public boolean supports(String mimeType) {
        return FileFormat.PDF.getMimeType().equals(mimeType);
    }

    /**
     * 能力名。
     *
     * @return 能力标识
     */
    @Override
    public String capabilityName() {
        return CAPABILITY;
    }

    /**
     * 能力版本。
     *
     * @return 能力版本号
     */
    @Override
    public String capabilityVersion() {
        return CAPABILITY_VERSION;
    }

    /**
     * 解析入口：委托文档流水线；取消原样抛出，其它异常统一包装。
     *
     * @param context 解析上下文
     * @return 解析结果（由单元集合派生的文档级汇总视图）
     */
    @Override
    public ParseSource parse(ParseContext context) {
        return ParseSource.fromDocument(parseDocument(context));
    }

    /**
     * 单元集合形式的解析入口（包内可见：单元契约的测试与后续组装消费）。
     *
     * @param context 解析上下文
     * @return 单元集合形式的解析结果
     */
    ParseDocument parseDocument(ParseContext context) {
        context.getCancellationToken().throwIfCancelled();
        String fileId = context.getFileRef().getFileId();
        try {
            return pipeline.run(context);
        } catch (CancellationException e) {
            throw e;
        } catch (Exception e) {
            log.warn("===> PdfBoxDocumentParser PDF 解析失败, fileId={}", fileId, e);
            throw new IllegalStateException("PDF 解析失败: " + e.getMessage(), e);
        }
    }
}
