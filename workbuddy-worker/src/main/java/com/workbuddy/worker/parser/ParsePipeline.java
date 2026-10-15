package com.workbuddy.worker.parser;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.workbuddy.common.domain.parse.BBox;
import com.workbuddy.common.domain.parse.CapabilitySnapshot;
import com.workbuddy.common.domain.parse.PageDimension;
import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseOutcome;
import com.workbuddy.common.domain.parse.ParseResult;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.domain.parse.QualityInfo;
import com.workbuddy.common.domain.parse.QualityWarning;
import com.workbuddy.common.domain.parse.capability.CapabilityUsage;
import com.workbuddy.common.domain.parse.capability.OcrLine;
import com.workbuddy.common.domain.parse.capability.OcrRegion;
import com.workbuddy.common.domain.parse.capability.ProviderContext;
import com.workbuddy.common.domain.parse.capability.StructuredOcrResult;
import com.workbuddy.common.domain.parse.signal.PageMetric;
import com.workbuddy.common.domain.task.StepLogInfo;
import com.workbuddy.common.domain.parse.signal.Signal;
import com.workbuddy.common.enums.parse.ElementType;
import com.workbuddy.common.enums.parse.ParseStepName;
import com.workbuddy.common.enums.parse.QualityWarningCode;
import com.workbuddy.common.enums.parse.SignalSubtype;
import com.workbuddy.common.enums.task.PipelineTaskErrorCode;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.enums.task.StepStatus;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.parser.capability.CapabilityFacts;
import com.workbuddy.worker.parser.capability.CapabilityRegistry;
import com.workbuddy.worker.parser.capability.StructuredOcrProvider;
import com.workbuddy.worker.parser.signal.SignalDetector;
import com.workbuddy.worker.parser.signal.SignalFallbackHandler;
import com.workbuddy.worker.pipeline.StepLogHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 解析环节编排（模板方法骨架）：文件级路由 → 原生解析 → 信号判定 →
 * 内置降级处置（策略：SignalFallbackHandler）→ 结果封装 → 成功占比门槛评估。
 * 骨架固定，变化点在策略（DocumentParserPort 解析器 / SignalFallbackHandler 信号处置）；
 * 纯算法，不碰 DB/产物存储（落库回写由 biz 编排）。
 *
 * @author cxxl
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ParsePipeline {

    private final List<DocumentParserPort> parsers;
    private final SignalDetector signalDetector;
    private final List<SignalFallbackHandler> fallbackHandlers;
    private final ParseProperties properties;
    private final CapabilityRegistry capabilityRegistry;

    public ParseOutcome run(ParseContext context) {
        ParseOutcome outcome = new ParseOutcome();
        ParseResult result = new ParseResult();
        result.setResultId(context.getFileResultId());
        result.setFile(context.getFileRef());
        QualityInfo quality = new QualityInfo();
        result.setQuality(quality);

        // ① 文件级路由
        StepLogInfo routeLog = StepLogHelper.begin(ParseStepName.FILE_ROUTE.value());
        String mimeType = context.getFileRef().getMimeType();
        DocumentParserPort parser = parsers.stream()
                .filter(p -> p.supports(mimeType))
                .findFirst()
                .orElse(null);
        if (NullUtil.isNull(parser)) {
            log.warn("===> ParsePipeline 无匹配解析器, fileResultId={}, mimeType={}",
                    context.getFileResultId(), mimeType);
            routeLog.setStatus(StepStatus.FAILED.name());
            routeLog.setError("无匹配解析器: " + mimeType);
            StepLogHelper.finish(routeLog);
            outcome.getStepLogs().add(routeLog);
            outcome.fail(PipelineTaskErrorCode.PARSE_FAILED.name(), "无匹配解析器: " + mimeType);
            return outcome;
        }
        routeLog.setCapabilityVersion(parser.capabilityName() + "-" + parser.capabilityVersion());
        routeLog.setStatus(StepStatus.SUCCESS.name());
        StepLogHelper.finish(routeLog);
        outcome.getStepLogs().add(routeLog);
        log.info("===> ParsePipeline 路由命中, fileResultId={}, mimeType={}, parser={}",
                context.getFileResultId(), mimeType, parser.capabilityName() + "-" + parser.capabilityVersion());

        // ② 原生解析
        StepLogInfo parseLog = StepLogHelper.begin(ParseStepName.NATIVE_PARSE.value());
        parseLog.setCapabilityVersion(parser.capabilityName() + "-" + parser.capabilityVersion());
        ParseSource nativeSource;
        try {
            // 解析
            nativeSource = parser.parse(context);
            parseLog.setStatus(StepStatus.SUCCESS.name());
        } catch (Exception e) {
            log.warn("===> ParsePipeline 原生解析失败, fileResultId={}, error={}",
                    context.getFileResultId(), StrUtil.maxLength(String.valueOf(e.getMessage()), 500));
            parseLog.setStatus(StepStatus.FAILED.name());
            parseLog.setError(StrUtil.maxLength(String.valueOf(e.getMessage()), 1000));
            StepLogHelper.finish(parseLog);
            outcome.getStepLogs().add(parseLog);
            outcome.fail(PipelineTaskErrorCode.PARSE_CORRUPTED.name(),
                    "解析失败: " + StrUtil.maxLength(String.valueOf(e.getMessage()), 500));
            return outcome;
        }
        StepLogHelper.finish(parseLog);
        outcome.getStepLogs().add(parseLog);
        log.info("===> ParsePipeline 原生解析完成, fileResultId={}, parser={}, elements={}, unitCount={}",
                context.getFileResultId(), parser.capabilityName() + "-" + parser.capabilityVersion(),
                nativeSource.getElements().size(), nativeSource.getUnitCount());

        // ③ 信号判定 + 内置降级处置
        // 扩展点：接入 OCR/版面/表格能力后，在此按信号类型查 CapabilityRegistry，
        // 把能力结果并入 result.sources 新增一路。
        StepLogInfo qualityLog = StepLogHelper.begin(ParseStepName.QUALITY_CHECK.value());
        List<Signal> signals = signalDetector.detect(nativeSource, context);
        int failedUnits = 0;
        for (Signal signal : signals) {
            failedUnits += applyFallback(signal, quality);
        }
        log.info("===> ParsePipeline 信号判定完成, fileResultId={}, signalCount={}, failedUnits={}",
                context.getFileResultId(), signals.size(), failedUnits);

        // ④ 结果封装：能力快照 + ocr 占位路
        CapabilitySnapshot snapshot = new CapabilitySnapshot();
        snapshot.setParserName(parser.capabilityName());
        snapshot.setParserVersion(parser.capabilityVersion());
        result.setCapabilitySnapshot(snapshot);
        ParseSource ocrSource = ocrSource(nativeSource);
        result.setSources(List.of(nativeSource, ocrSource));
        fillCapabilityRefs(snapshot, List.of(nativeSource, ocrSource));

        // ⑤ 成功占比门槛评估
        evaluate(outcome, nativeSource, failedUnits, quality);
        summarizeQuality(nativeSource, quality); // ⑥ 坏页比例达门槛时补一条维度汇总告警

        // 失败诊断留痕：告警明细进质量检查子步骤（落 kb_pipeline_step_log.error，可查询）
        if (outcome.getSuggestedStatus() == PipelineTaskStatus.FAILED) {
            String summary = quality.getWarnings().stream()
                    .map(w -> w.getCode() + ":" + w.getMessage())
                    .collect(Collectors.joining("; "));
            qualityLog.setError(StrUtil.maxLength(summary, 1000));
        }

        qualityLog.setWarningCount(quality.getWarnings().size());
        qualityLog.setStatus(StepStatus.SUCCESS.name());
        StepLogHelper.finish(qualityLog);
        outcome.getStepLogs().add(qualityLog);
        outcome.setParseResult(result);
        return outcome;
    }

    /** 内置降级处置：按信号子类型查找 handler 执行（不依赖 evidence 文案）；返回该信号计入的失败单元数 */
    private int applyFallback(Signal signal, QualityInfo quality) {
        SignalSubtype subtype = parseSubtype(signal);
        if (NullUtil.isNull(subtype)) {
            return 0;
        }
        return fallbackHandlers.stream()
                .filter(handler -> handler.subtype() == subtype)
                .findFirst()
                .map(handler -> handler.handle(signal, quality))
                .orElse(0);
    }

    /** 信号子类型解析：未设置/未知值返回 null（防御，不判失败）。 */
    private SignalSubtype parseSubtype(Signal signal) {
        if (StrUtil.isBlank(signal.getSubtype())) {
            return null;
        }
        try {
            return SignalSubtype.valueOf(signal.getSubtype());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * ocr 路：整页无文本且含图片的页交结构化 OCR，识别结果按行产 PARAGRAPH 元素（`needsOcr=false`）并记补强事实；
     * 未接入能力、无可识别页或某页失败时按页记降级事实，路说明沿用占位文案。
     * 与 native 路的重叠区域仍由组装环节裁决，本路不做合并。
     */
    private ParseSource ocrSource(ParseSource nativeSource) {
        ParseSource ocr = new ParseSource();
        ocr.setSource("ocr");
        ocr.setCandidateOrder(false);
        ocr.setNote("预留未开放：接入后扫描/图片区域文字走本路，与 native 路重叠区域由组装环节裁决");
        StructuredOcrProvider provider = NullUtil.isNull(capabilityRegistry) ? null : capabilityRegistry.ocr();
        if (NullUtil.isNull(provider)) {
            return ocr;
        }
        int filledPages = 0;
        for (PageMetric metric : ObjectUtil.defaultIfNull(nativeSource.getPageMetrics(), List.<PageMetric>of())) {
            if (metric.getCharCount() > 0 || !hasImage(metric)) {
                continue;
            }
            String region = "page " + metric.getPage();
            OcrRegion ocrRegion = new OcrRegion();
            ocrRegion.setPage(metric.getPage());
            ocrRegion.setImageRef("page" + metric.getPage() + "-ocr");
            ocrRegion.setBbox(pageBox(nativeSource, metric.getPage()));
            StructuredOcrResult recognized = provider.recognize(ocrRegion, new ProviderContext());
            if (NullUtil.isNull(recognized) || NullUtil.isNull(recognized.getLines())
                    || recognized.getLines().isEmpty()) {
                ocr.getFacts().add(CapabilityFacts.ocrDegraded(region, "调用失败或结果不完整"));
                continue;
            }
            filledPages++;
            for (OcrLine line : recognized.getLines()) {
                ocr.getElements().add(ocrParagraph(line, metric.getPage()));
            }
            ocr.getFacts().add(CapabilityFacts.ocrEnhanced(region, recognized));
            if (ocr.getCapabilities().isEmpty()) {
                ocr.getCapabilities().add(
                        new CapabilityUsage(CapabilityUsage.KIND_OCR, recognized.getModel(), recognized.getVersion()));
            }
        }
        if (filledPages == 0) {
            ocr.setNote("OCR 能力已接入但本档无可补文字的页（整页无文本且含图片的页）");
        }
        return ocr;
    }

    /** 识别行转段落元素：文本、页内包围盒与置信度，ID 与溯源归组装环节 */
    private ParseElement ocrParagraph(OcrLine line, Integer pageNo) {
        ParseElement element = ParseElement.of(ElementType.PARAGRAPH);
        element.setText(line.getText());
        element.setPage(pageNo);
        element.setBbox(line.getBbox());
        element.setConfidence(line.getConfidence());
        element.setNeedsOcr(false);
        return element;
    }

    /** 该单元是否含图片：Office 看内嵌图片数，PDF 看图片覆盖占比 */
    private boolean hasImage(PageMetric metric) {
        if (NullUtil.isNotNull(metric.getImageCount()) && metric.getImageCount() > 0) {
            return true;
        }
        return metric.getImageAreaRatio() >= properties.getScanPageImageRatio();
    }

    /** 页包围盒：按页尺寸取整页（页尺寸缺失时给 null，区域仍可发起调用） */
    private BBox pageBox(ParseSource nativeSource, Integer pageNo) {
        for (PageDimension dimension : ObjectUtil.defaultIfNull(nativeSource.getPageDimensions(),
                List.<PageDimension>of())) {
            if (NullUtil.isNotNull(pageNo) && pageNo.equals(dimension.getPage())) {
                return new BBox(0, 0, dimension.getWidth(), dimension.getHeight());
            }
        }
        return null;
    }

    /**
     * 能力快照回填：把各路实际用到的模型能力（模型名 + 版本）写进快照对应项，
     * 未走能力的类别保持 null（表示本次解析未使用该类能力）。
     */
    private void fillCapabilityRefs(CapabilitySnapshot snapshot, List<ParseSource> sources) {
        for (ParseSource source : sources) {
            for (CapabilityUsage usage : ObjectUtil.defaultIfNull(source.getCapabilities(),
                    List.<CapabilityUsage>of())) {
                CapabilitySnapshot.CapabilityRef ref = new CapabilitySnapshot.CapabilityRef();
                ref.setModel(usage.model());
                ref.setVersion(usage.version());
                if (usage.isKind(CapabilityUsage.KIND_OCR)) {
                    snapshot.setOcr(ref);
                } else if (usage.isKind(CapabilityUsage.KIND_LAYOUT)) {
                    snapshot.setLayout(ref);
                } else if (usage.isKind(CapabilityUsage.KIND_TABLE)) {
                    snapshot.setTable(ref);
                }
            }
        }
    }

    /**
     * 质量维度汇总：坏页比例（登记失败页数 ÷ 内容单元）达到 `qualityBadPageRatioThreshold` 时，
     * 额外追加一条 `QUALITY_BAD_PAGE_RATIO` 汇总告警，消息按维度列出各类计数（逐条告警语义不变）。
     */
    private void summarizeQuality(ParseSource nativeSource, QualityInfo quality) {
        int unitCount = NullUtil.isNull(nativeSource.getUnitCount()) ? 0 : nativeSource.getUnitCount();
        int contentUnits = unitCount - (int) quality.getBlankPages().stream().distinct().count();
        if (contentUnits <= 0) {
            return;
        }
        double badRatio = (double) quality.getFailedPages().size() / contentUnits;
        if (badRatio < properties.getQualityBadPageRatioThreshold()) {
            return;
        }
        quality.getWarnings().add(QualityWarning.of(QualityWarningCode.QUALITY_BAD_PAGE_RATIO, null, "WARN",
                String.format("坏页 %d/%d 个内容单元（%.2f ≥ %.2f）：%s", quality.getFailedPages().size(),
                        contentUnits, badRatio, properties.getQualityBadPageRatioThreshold(),
                        dimensionCounts(quality))));
    }

    /** 汇总清单文案：各告警码条数（质量维度计数），全零时给「无异常维度」 */
    private String dimensionCounts(QualityInfo quality) {
        Map<String, Long> counts = quality.getWarnings().stream()
                .collect(Collectors.groupingBy(QualityWarning::getCode, LinkedHashMap::new,
                        Collectors.counting()));
        if (counts.isEmpty()) {
            return "无异常维度";
        }
        return counts.entrySet().stream()
                .map(entry -> entry.getKey() + " " + entry.getValue())
                .collect(Collectors.joining(" / "));
    }

    /**
     * 成功占比门槛评估：0 单元→PARSE_CORRUPTED；全空白页→PARSE_CORRUPTED；
     * 内容单元全为扫描页→SCANNED_UNSUPPORTED；短文档看坏页绝对数；占比不足→RATIO_BELOW_THRESHOLD。
     * 分母用**内容单元**（单元数 − 空白页数）：空白页无内容可解析，既不算成功也不算失败。
     */
    private void evaluate(ParseOutcome outcome, ParseSource nativeSource, int failedUnits, QualityInfo quality) {
        int unitCount = NullUtil.isNull(nativeSource.getUnitCount()) ? 0 : nativeSource.getUnitCount();
        outcome.setUnitCount(unitCount);
        outcome.setFailedUnits(failedUnits);
        if (unitCount <= 0) {
            outcome.fail(PipelineTaskErrorCode.PARSE_CORRUPTED.name(), "无有效内容单元（0 页/0 sheet）");
            return;
        }
        int blankUnits = (int) quality.getBlankPages().stream().distinct().count();
        int contentUnits = unitCount - blankUnits;
        if (contentUnits <= 0) {
            outcome.fail(PipelineTaskErrorCode.PARSE_CORRUPTED.name(),
                    "全为空白页（" + unitCount + " 个单元无文本也无图片）");
            return;
        }
        // 纯扫描件：内容单元全判为扫描页 → 整任务 FAILED
        if (!quality.getScannedPages().isEmpty() && quality.getScannedPages().size() >= contentUnits) {
            outcome.fail(PipelineTaskErrorCode.SCANNED_UNSUPPORTED.name(), "扫描件暂不支持（OCR 预留）");
            return;
        }
        // 短文档：小分母上比例门槛等价零容错，改看坏页绝对数（坏页 ≤ 门槛且仍有可读单元即放行）
        if (contentUnits <= properties.getShortDocumentMaxUnits()
                && failedUnits <= properties.getShortDocumentMaxFailedUnits()
                && contentUnits - failedUnits >= 1) {
            outcome.setSuggestedStatus(failedUnits == 0
                    ? PipelineTaskStatus.SUCCESS
                    : PipelineTaskStatus.PARTIAL_SUCCESS);
            return;
        }
        double ratio = (double) (contentUnits - failedUnits) / contentUnits;
        if (ratio >= properties.getSuccessUnitRatio()) {
            outcome.setSuggestedStatus(failedUnits == 0
                    ? PipelineTaskStatus.SUCCESS
                    : PipelineTaskStatus.PARTIAL_SUCCESS);
            return;
        }
        // 门槛不足：连每页原始指标一起打印（扫描件/乱码口径/文字占比口径）
        log.warn("解析成功占比不足, unitCount={}, blankUnits={}, failedUnits={}, ratio={}, threshold={}, pageMetrics={}",
                unitCount, blankUnits, failedUnits, String.format("%.2f", ratio),
                properties.getSuccessUnitRatio(),
                nativeSource.getPageMetrics().stream()
                        .map(m -> m.getPage() + ":chars=" + m.getCharCount()
                                + ",garbled=" + String.format("%.2f", m.getGarbledRatio())
                                + ",area=" + String.format("%.2f", m.getTextAreaRatio())
                                + ",image=" + String.format("%.2f", m.getImageAreaRatio()))
                        .toList());
        outcome.fail(PipelineTaskErrorCode.RATIO_BELOW_THRESHOLD.name(),
                String.format("成功单元占比 %.2f 低于门槛 %.2f（内容单元 %d，失败 %d）",
                        ratio, properties.getSuccessUnitRatio(), contentUnits, failedUnits));
    }
}
