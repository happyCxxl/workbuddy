package com.workbuddy.worker.parser;

import com.workbuddy.common.domain.input.FileReference;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.domain.parse.capability.CapabilityUsage;
import com.workbuddy.common.domain.parse.signal.PageMetric;
import com.workbuddy.common.domain.parse.signal.ParseFact;
import com.workbuddy.worker.parser.capability.CapabilityRegistry;
import com.workbuddy.worker.parser.signal.impl.SignalDetectorImpl;
import com.workbuddy.worker.parser.signal.SignalFallbackHandler;
import com.workbuddy.worker.parser.signal.fallback.BlankFallbackHandler;
import com.workbuddy.worker.parser.signal.fallback.CidMappingFallbackHandler;
import com.workbuddy.worker.parser.signal.fallback.GarbledFallbackHandler;
import com.workbuddy.worker.parser.signal.fallback.ImageEmbeddedFallbackHandler;
import com.workbuddy.worker.parser.signal.fallback.ImageLowRatioFallbackHandler;
import com.workbuddy.worker.parser.signal.fallback.LayoutRuleFailedFallbackHandler;
import com.workbuddy.worker.parser.signal.fallback.ScannedFallbackHandler;
import com.workbuddy.worker.parser.signal.fallback.TableRuleFailedFallbackHandler;
import com.workbuddy.worker.parser.signal.fallback.VectorPageFallbackHandler;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 解析管线测试共享夹具：解析阈值、信号检测器、上下文、假解析器与判定单元指标构造。
 */
final class ParsePipelineTestFixture {

    private final ParseProperties properties = new ParseProperties();
    private final SignalDetectorImpl signalDetector = new SignalDetectorImpl();

    /**
     * 解析阈值（用例可改配置项）。
     *
     * @return 解析阈值
     */
    ParseProperties properties() {
        return properties;
    }

    /** 九个信号降级 handler 真实实例（无 mock） */
    private List<SignalFallbackHandler> fallbackHandlers() {
        return List.of(new GarbledFallbackHandler(), new CidMappingFallbackHandler(), new ScannedFallbackHandler(),
                new BlankFallbackHandler(), new VectorPageFallbackHandler(), new ImageEmbeddedFallbackHandler(),
                new ImageLowRatioFallbackHandler(), new TableRuleFailedFallbackHandler(),
                new LayoutRuleFailedFallbackHandler());
    }

    /**
     * 构造解析上下文。
     *
     * @param mimeType 媒体类型
     * @return 解析上下文
     */
    ParseContext context(String mimeType) {
        ParseContext context = new ParseContext();
        context.setFileResultId(1L);
        FileReference fileRef = new FileReference();
        fileRef.setFileId("F-1");
        fileRef.setFileName("t");
        fileRef.setMimeType(mimeType);
        context.setFileRef(fileRef);
        context.setInputStream(new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)));
        context.setProperties(properties);
        return context;
    }

    /**
     * 假解析器。
     *
     * @param metrics 页级指标
     * @param facts   解析事实
     * @return 假解析器
     */
    DocumentParserPort fakeParser(List<PageMetric> metrics, List<ParseFact> facts) {
        return fakeParser(metrics, facts, List.of());
    }

    /**
     * 假解析器（可带本路用到的能力引用）。
     *
     * @param metrics      页级指标
     * @param facts        解析事实
     * @param capabilities 能力引用
     * @return 假解析器
     */
    DocumentParserPort fakeParser(List<PageMetric> metrics, List<ParseFact> facts,
                                  List<CapabilityUsage> capabilities) {
        return new DocumentParserPort() {
            @Override
            public boolean supports(String mime) {
                return "application/pdf".equals(mime);
            }

            @Override
            public ParseSource parse(ParseContext context) {
                ParseSource source = ParseSource.nativeSource("fake-1.0");
                source.setUnitCount(metrics.size());
                source.setPageMetrics(metrics);
                source.setFacts(facts);
                source.setCapabilities(capabilities);
                return source;
            }

            @Override
            public String capabilityName() {
                return "fake";
            }

            @Override
            public String capabilityVersion() {
                return "1.0";
            }
        };
    }

    /**
     * 构造管线。
     *
     * @param parser 解析器
     * @return 管线
     */
    ParsePipeline pipeline(DocumentParserPort parser) {
        return pipeline(parser, null);
    }

    /**
     * 构造管线（可带能力注册表）。
     *
     * @param parser             解析器
     * @param capabilityRegistry 能力注册表（可空）
     * @return 管线
     */
    ParsePipeline pipeline(DocumentParserPort parser, CapabilityRegistry capabilityRegistry) {
        return new ParsePipeline(List.of(parser), signalDetector, fallbackHandlers(), properties, capabilityRegistry);
    }

    /**
     * 正常页：文本充足、乱码率低、文字占比正常。
     *
     * @param page 页码
     * @return 页级指标
     */
    PageMetric normalPage(int page) {
        PageMetric metric = new PageMetric();
        metric.setPage(page);
        metric.setCharCount(500);
        metric.setGarbledRatio(0.01);
        metric.setTextAreaRatio(0.3);
        return metric;
    }

    /**
     * 扫描页：整页无文本 + 图片覆盖高。
     *
     * @param page 页码
     * @return 页级指标
     */
    PageMetric scannedPage(int page) {
        PageMetric metric = new PageMetric();
        metric.setPage(page);
        metric.setCharCount(0);
        metric.setGarbledRatio(0);
        metric.setTextAreaRatio(0);
        metric.setImageAreaRatio(0.8);
        return metric;
    }

    /**
     * 空白页：整页无文本也无图片。
     *
     * @param page 页码
     * @return 页级指标
     */
    PageMetric blankPage(int page) {
        PageMetric metric = new PageMetric();
        metric.setPage(page);
        metric.setCharCount(0);
        metric.setGarbledRatio(0);
        metric.setTextAreaRatio(0);
        return metric;
    }

    /**
     * 文字极少页：有文本但少于扫描页门槛且含图片（疑似图片单元，仅告警）。
     *
     * @param page 页码
     * @return 页级指标
     */
    PageMetric sparseTextPage(int page) {
        PageMetric metric = new PageMetric();
        metric.setPage(page);
        metric.setCharCount(10);
        metric.setGarbledRatio(0);
        metric.setTextAreaRatio(0.3);
        metric.setImageCount(1);
        return metric;
    }

    /**
     * Office 图片单元：整单元无文本 + 内嵌图片（Word 全图档 / 只放图的 sheet）。
     *
     * @param unit 单元页码
     * @return 页级指标
     */
    PageMetric imageOnlyUnit(int unit) {
        PageMetric metric = new PageMetric();
        metric.setPage(unit);
        metric.setCharCount(0);
        metric.setImageCount(1);
        return metric;
    }

    /**
     * 乱码页。
     *
     * @return 页级指标
     */
    PageMetric garbledPage() {
        PageMetric metric = new PageMetric();
        metric.setPage(1);
        metric.setCharCount(500);
        metric.setGarbledRatio(0.9);
        metric.setTextAreaRatio(0.3);
        return metric;
    }

    /**
     * 乱码页且字符主要来自缺 Unicode 映射的 CID 字体。
     *
     * @return 页级指标
     */
    PageMetric cidMappingFailedPage() {
        PageMetric metric = garbledPage();
        metric.setCidMappingRatio(0.95);
        return metric;
    }
}
