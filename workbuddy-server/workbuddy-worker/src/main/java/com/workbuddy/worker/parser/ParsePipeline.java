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
import com.workbuddy.common.domain.parse.ParseSummary;
import com.workbuddy.common.domain.parse.QualityInfo;
import com.workbuddy.common.domain.parse.QualityWarning;
import com.workbuddy.common.domain.parse.ParseAsset;
import com.workbuddy.common.domain.parse.capability.CapabilityUsage;
import com.workbuddy.common.domain.parse.capability.OcrLine;
import com.workbuddy.common.domain.parse.capability.OcrRegion;
import com.workbuddy.common.domain.parse.capability.ProviderContext;
import com.workbuddy.common.domain.parse.capability.StructuredOcrResult;
import com.workbuddy.common.enums.parse.ParseAssetKind;
import com.workbuddy.common.domain.task.StepLogInfo;
import com.workbuddy.common.domain.parse.signal.Signal;
import com.workbuddy.common.enums.parse.ElementType;
import com.workbuddy.common.enums.parse.ParseStepName;
import com.workbuddy.common.enums.parse.QualityWarningCode;
import com.workbuddy.common.enums.parse.SignalSubtype;
import com.workbuddy.common.enums.task.PipelineTaskErrorCode;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.enums.task.StepStatus;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.filecenter.util.FileUtil;
import com.workbuddy.worker.parser.capability.CapabilityFacts;
import com.workbuddy.worker.parser.capability.CapabilityRegistry;
import com.workbuddy.worker.parser.capability.StructuredOcrProvider;
import com.workbuddy.worker.parser.asset.ParseAssetException;
import com.workbuddy.worker.parser.pdf.store.PageArtifactStoreException;
import com.workbuddy.worker.parser.signal.SignalDetector;
import com.workbuddy.worker.parser.signal.SignalFallbackHandler;
import com.workbuddy.worker.pipeline.StepLogHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.poi.EncryptedDocumentException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;

/**
 * 解析环节编排（模板方法骨架）：文件级路由 → 原生解析 → 信号判定 →
 * 内置降级处置（策略：SignalFallbackHandler）→ 结果封装 → 成功占比门槛评估。
 * 骨架固定，变化点在策略（DocumentParserPort 解析器 / SignalFallbackHandler 信号处置）；
 * 资产经 {@code ParseContext.assetSink} 写入（由 biz 注入对象存储实现），本类不直接落库。
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

    /**
     * 解析环节入口：按格式选解析器并产出原生结构。
     *
     * @param context 环节上下文
     * @return 环节产出
     */
    public ParseOutcome run(ParseContext context) {
        ParseOutcome outcome = new ParseOutcome();
        ParseResult result = new ParseResult();
        result.setResultId(context.getFileResultId());
        result.setFile(context.getFileRef());
        QualityInfo quality = new QualityInfo();
        result.setQuality(quality);
        if (context.getCancellationToken().isCancelled()) {
            outcome.fail(PipelineTaskErrorCode.PARSE_TIMEOUT.name(), "解析已取消（超时/停机）");
            return outcome;
        }

        // ① 文件级路由
        StepLogInfo routeLog = StepLogHelper.begin(ParseStepName.FILE_ROUTE.value());
        String rawMimeType = context.getFileRef().getMimeType();
        String normalizedMime = FileUtil.normalizeMime(rawMimeType, context.getFileRef().getFileName());
        String mimeType = NullUtil.isNotNull(normalizedMime) ? normalizedMime : rawMimeType;
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
            outcome.fail(PipelineTaskErrorCode.PARSE_UNSUPPORTED_FORMAT.name(), "格式不支持: " + mimeType);
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
        } catch (CancellationException e) {
            parseLog.setStatus(StepStatus.FAILED.name());
            parseLog.setError("解析已取消（超时/停机）");
            StepLogHelper.finish(parseLog);
            outcome.getStepLogs().add(parseLog);
            outcome.fail(PipelineTaskErrorCode.PARSE_TIMEOUT.name(), "解析已取消（超时/停机）");
            return outcome;
        } catch (Exception e) {
            log.warn("===> ParsePipeline 原生解析失败, fileResultId={}, error={}",
                    context.getFileResultId(), StrUtil.maxLength(String.valueOf(e.getMessage()), 500));
            parseLog.setStatus(StepStatus.FAILED.name());
            parseLog.setError(StrUtil.maxLength(String.valueOf(e.getMessage()), 1000));
            StepLogHelper.finish(parseLog);
            outcome.getStepLogs().add(parseLog);
            PipelineTaskErrorCode failureCode = classifyParseFailure(e);
            String failureText = failureCode == PipelineTaskErrorCode.PARSE_ENCRYPTED
                    ? "文件已加密，无法解析: " : "解析失败: ";
            outcome.fail(failureCode.name(),
                    failureText + StrUtil.maxLength(String.valueOf(e.getMessage()), 500));
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
        ParseSource ocrSource;
        try {
            ocrSource = ocrSource(nativeSource, quality, context);
        } catch (CancellationException e) {
            outcome.fail(PipelineTaskErrorCode.PARSE_TIMEOUT.name(), "解析已取消（超时/停机）");
            return outcome;
        }
        result.setSources(List.of(nativeSource, ocrSource));
        fillCapabilityRefs(snapshot, List.of(nativeSource, ocrSource));

        // ⑤ 成功占比门槛评估（OCR 覆盖的扫描页按恢复处理）
        if (context.getCancellationToken().isCancelled()) {
            outcome.fail(PipelineTaskErrorCode.PARSE_TIMEOUT.name(), "解析已取消（超时/停机）");
            return outcome;
        }
        evaluate(outcome, nativeSource, ocrSource, failedUnits, quality);
        // ⑥ 坏页比例达门槛时补一条维度汇总告警
        summarizeQuality(nativeSource, quality);
        ParseSummary parseSummary = buildSummary(outcome, nativeSource, ocrSource, quality);
        parseSummary.setAssetCount(context.getAssetSink().assets().size());
        result.setSummary(parseSummary);
        result.setAssets(context.getAssetSink().assets());

        // 失败诊断留痕：告警明细进质量检查子步骤（落 wb_knowledge_pipeline_step_log.error，可查询）
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

    /** 原生解析异常分类：沿 cause 链识别加密异常与页产物存储失败，其余归为文件损坏。 */
    private PipelineTaskErrorCode classifyParseFailure(Throwable error) {
        Throwable cursor = error;
        while (NullUtil.isNotNull(cursor)) {
            if (cursor instanceof InvalidPasswordException || cursor instanceof EncryptedDocumentException) {
                return PipelineTaskErrorCode.PARSE_ENCRYPTED;
            }
            if (cursor instanceof PageArtifactStoreException || cursor instanceof ParseAssetException) {
                return PipelineTaskErrorCode.PARSE_FAILED;
            }
            cursor = cursor.getCause();
        }
        return PipelineTaskErrorCode.PARSE_CORRUPTED;
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
     * ocr 路：触发集 = 扫描页 ∪ 失败页（乱码/CID 缺映射）；整页图交结构化 OCR，识别结果按行产
     * PARAGRAPH 元素（`needsOcr=false`）并记补强事实；未接入能力、无可识别页或某页失败时按页记降级事实。
     * 与 native 路的重叠区域仍由组装环节裁决，本路不做合并。
     */
    private ParseSource ocrSource(ParseSource nativeSource, QualityInfo quality, ParseContext context) {
        ParseSource ocr = new ParseSource();
        ocr.setSource("ocr");
        ocr.setCandidateOrder(false);
        ocr.setNote("OCR 能力未接入：接入后扫描/乱码页文字走本路，与 native 路重叠区域由组装环节裁决");
        StructuredOcrProvider provider = NullUtil.isNull(capabilityRegistry) ? null : capabilityRegistry.ocr();
        if (NullUtil.isNull(provider)) {
            return ocr;
        }
        Set<Integer> candidates = new TreeSet<>();
        candidates.addAll(quality.getScannedPages());
        candidates.addAll(quality.getFailedPages());
        int filledPages = 0;
        for (Integer pageNo : candidates) {
            context.getCancellationToken().throwIfCancelled();
            String region = "page " + pageNo;
            OcrRegion ocrRegion = new OcrRegion();
            ocrRegion.setPage(pageNo);
            ocrRegion.setImageRef(pageAssetId(context, pageNo));
            ocrRegion.setBbox(pageBox(nativeSource, pageNo));
            ProviderContext providerContext = new ProviderContext();
            providerContext.setTaskId(context.getFileResultId());
            providerContext.setAssetBytes(imageRef -> context.getAssetSink().read(imageRef));
            StructuredOcrResult recognized;
            try {
                recognized = provider.recognize(ocrRegion, providerContext);
            } catch (RuntimeException e) {
                log.warn("===> ParsePipeline OCR 识别失败, fileResultId={}, region={}, error={}",
                        context.getFileResultId(), region,
                        StrUtil.maxLength(String.valueOf(e.getMessage()), 200));
                recognized = null;
            }
            if (NullUtil.isNull(recognized) || NullUtil.isNull(recognized.getLines())
                    || recognized.getLines().isEmpty()) {
                ocr.getFacts().add(CapabilityFacts.ocrDegraded(region, "调用失败或结果不完整"));
                continue;
            }
            filledPages++;
            for (OcrLine line : recognized.getLines()) {
                ocr.getElements().add(ocrParagraph(line, pageNo));
            }
            ocr.getFacts().add(CapabilityFacts.ocrEnhanced(region, recognized));
            if (ocr.getCapabilities().isEmpty()) {
                ocr.getCapabilities().add(
                        new CapabilityUsage(CapabilityUsage.KIND_OCR, recognized.getModel(), recognized.getVersion()));
            }
        }
        ocr.setNote(filledPages == 0
                ? "OCR 能力已接入但本档无可补文字的页（扫描页与乱码/CID 失败页）"
                : "OCR 能力已接入：" + filledPages + " 页文字由能力补出，与 native 路重叠区域由组装环节裁决");
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

    /** 页图资产 ID：同一页有多张时取第一张；没有页图时返回 null（OCR 仍可按页发起）。 */
    private String pageAssetId(ParseContext context, Integer pageNo) {
        if (NullUtil.isNull(pageNo)) {
            return null;
        }
        return context.getAssetSink().assets().stream()
                .filter(asset -> ParseAssetKind.PAGE_IMAGE == asset.getKind())
                .filter(asset -> pageNo.equals(asset.getPage()))
                .map(ParseAsset::getAssetId)
                .findFirst()
                .orElse(null);
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
                ref.setModel(usage.getModel());
                ref.setVersion(usage.getVersion());
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

    /** 产物摘要：计数、失败页、阈值快照与配置哈希，随 ParseResult 落产物。 */
    private ParseSummary buildSummary(ParseOutcome outcome, ParseSource nativeSource,
                                      ParseSource ocrSource, QualityInfo quality) {
        ParseSummary summary = new ParseSummary();
        Set<Integer> blankPages = quality.getBlankPages().stream().distinct().collect(Collectors.toSet());
        Set<Integer> scannedPages = scannedPages(quality, blankPages);
        Set<Integer> ocrPages = ocrPages(ocrSource);
        int unitCount = outcome.getUnitCount();
        summary.setUnitCount(unitCount);
        summary.setFailedUnits(outcome.getFailedUnits());
        summary.setBlankUnits(blankPages.size());
        summary.setScannedUnits(scannedPages.size());
        summary.setOcrPages(ocrPages.size());
        summary.setOcrRecoveredUnits((int) scannedPages.stream().filter(ocrPages::contains).count());
        summary.setReadableUnits(Math.max(0, unitCount - blankPages.size() - outcome.getFailedUnits()));
        summary.setDegradedElements(degradedElements(nativeSource, ocrSource));
        summary.setFailedPages(quality.getFailedPages().stream().distinct().sorted().toList());
        summary.setThresholds(thresholds());
        summary.setConfigHash(configHash());
        return summary;
    }

    /** 元素降级数：native 路与 ocr 路合计。 */
    private int degradedElements(ParseSource nativeSource, ParseSource ocrSource) {
        int count = 0;
        for (ParseSource source : List.of(nativeSource, ocrSource)) {
            if (NullUtil.isNull(source) || NullUtil.isNull(source.getElements())) {
                continue;
            }
            count += (int) source.getElements().stream()
                    .filter(element -> Boolean.TRUE.equals(element.getDegraded()))
                    .count();
        }
        return count;
    }

    /** 判定关键阈值快照（kebab-case 键，便于与配置面直接对照）。 */
    private Map<String, Object> thresholds() {
        Map<String, Object> thresholds = new LinkedHashMap<>();
        thresholds.put("page-window-size", properties.getPageWindowSize());
        thresholds.put("large-file-min-bytes", properties.getLargeFileMinBytes());
        thresholds.put("office-page-chars", properties.getOfficePageChars());
        thresholds.put("page-image-dpi", properties.getPageImageDpi());
        thresholds.put("scan-page-min-chars", properties.getScanPageMinChars());
        thresholds.put("scan-page-image-ratio", properties.getScanPageImageRatio());
        thresholds.put("garbled-rate-threshold", properties.getGarbledRateThreshold());
        thresholds.put("text-area-ratio-threshold", properties.getTextAreaRatioThreshold());
        thresholds.put("success-unit-ratio", properties.getSuccessUnitRatio());
        thresholds.put("short-document-max-units", properties.getShortDocumentMaxUnits());
        thresholds.put("short-document-max-failed-units", properties.getShortDocumentMaxFailedUnits());
        thresholds.put("quality-bad-page-ratio-threshold", properties.getQualityBadPageRatioThreshold());
        return thresholds;
    }

    /** 全量解析配置序列化后的 SHA-256。 */
    private String configHash() {
        try {
            byte[] bytes = JsonUtil.toJsonStr(properties).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 成功占比门槛评估：0 单元→PARSE_CORRUPTED；全空白页→PARSE_CORRUPTED；
     * 扫描页按 OCR 覆盖拆成恢复/未恢复：未恢复计失败，全恢复按 PARTIAL_SUCCESS 放行；
     * 短文档看坏页绝对数；占比不足→RATIO_BELOW_THRESHOLD。
     * 分母用**内容单元**（单元数 − 空白页数）：空白页无内容可解析，既不算成功也不算失败。
     */
    private void evaluate(ParseOutcome outcome, ParseSource nativeSource, ParseSource ocrSource,
                          int failedUnits, QualityInfo quality) {
        int unitCount = NullUtil.isNull(nativeSource.getUnitCount()) ? 0 : nativeSource.getUnitCount();
        outcome.setUnitCount(unitCount);
        outcome.setFailedUnits(failedUnits);
        if (unitCount <= 0) {
            outcome.fail(PipelineTaskErrorCode.PARSE_CORRUPTED.name(), "无有效内容单元（0 页/0 sheet）");
            return;
        }
        Set<Integer> blankPages = quality.getBlankPages().stream().distinct().collect(Collectors.toSet());
        int contentUnits = unitCount - blankPages.size();
        if (contentUnits <= 0) {
            outcome.fail(PipelineTaskErrorCode.PARSE_CORRUPTED.name(),
                    "全为空白页（" + unitCount + " 个单元无文本也无图片）");
            return;
        }
        // 扫描页不再由 handler 直接计失败：OCR 覆盖的页算恢复，未覆盖的页由门禁计入失败
        Set<Integer> scannedPages = scannedPages(quality, blankPages);
        Set<Integer> ocrPages = ocrPages(ocrSource);
        Set<Integer> recoveredPages = scannedPages.stream()
                .filter(ocrPages::contains)
                .collect(Collectors.toSet());
        Set<Integer> unrecoveredPages = scannedPages.stream()
                .filter(page -> !ocrPages.contains(page))
                .collect(Collectors.toSet());
        int additionalFailedUnits = 0;
        for (Integer page : unrecoveredPages) {
            if (!quality.getFailedPages().contains(page)) {
                quality.getFailedPages().add(page);
                additionalFailedUnits++;
            }
        }
        // 乱码/CID 失败页被 OCR 覆盖即恢复：从失败计数扣除并移出失败页清单
        int recoveredFailedUnits = (int) quality.getFailedPages().stream()
                .filter(ocrPages::contains)
                .count();
        quality.getFailedPages().removeIf(ocrPages::contains);
        int effectiveFailedUnits = failedUnits + additionalFailedUnits - recoveredFailedUnits;
        outcome.setFailedUnits(effectiveFailedUnits);

        // 纯扫描件且 OCR 一页未恢复：能力未覆盖，整任务 FAILED；部分恢复时继续按占比判定
        if (!scannedPages.isEmpty() && scannedPages.size() >= contentUnits) {
            if (recoveredPages.isEmpty()) {
                outcome.fail(PipelineTaskErrorCode.SCANNED_UNSUPPORTED.name(),
                        "扫描件暂不支持（OCR 未覆盖扫描页）");
                return;
            }
            if (recoveredPages.containsAll(scannedPages)) {
                outcome.setSuggestedStatus(PipelineTaskStatus.PARTIAL_SUCCESS);
                return;
            }
        }
        // 短文档：小分母上比例门槛等价零容错，改看坏页绝对数（坏页 ≤ 门槛且仍有可读单元即放行）
        if (contentUnits <= properties.getShortDocumentMaxUnits()
                && effectiveFailedUnits <= properties.getShortDocumentMaxFailedUnits()
                && contentUnits - effectiveFailedUnits >= 1) {
            outcome.setSuggestedStatus(effectiveFailedUnits == 0 && recoveredPages.isEmpty()
                    ? PipelineTaskStatus.SUCCESS
                    : PipelineTaskStatus.PARTIAL_SUCCESS);
            return;
        }
        double ratio = (double) (contentUnits - effectiveFailedUnits) / contentUnits;
        if (ratio >= properties.getSuccessUnitRatio()) {
            outcome.setSuggestedStatus(effectiveFailedUnits == 0 && recoveredPages.isEmpty()
                    ? PipelineTaskStatus.SUCCESS
                    : PipelineTaskStatus.PARTIAL_SUCCESS);
            return;
        }
        // 门槛不足：连每页原始指标一起打印（扫描件/乱码口径/文字占比口径）
        log.warn("===> ParsePipeline 解析成功占比不足, unitCount={}, blankUnits={}, failedUnits={}, "
                        + "ratio={}, threshold={}, pageMetrics={}",
                unitCount, blankPages.size(), effectiveFailedUnits, String.format("%.2f", ratio),
                properties.getSuccessUnitRatio(),
                nativeSource.getPageMetrics().stream()
                        .map(m -> m.getPage() + ":chars=" + m.getCharCount()
                                + ",garbled=" + String.format("%.2f", m.getGarbledRatio())
                                + ",area=" + String.format("%.2f", m.getTextAreaRatio())
                                + ",image=" + String.format("%.2f", m.getImageAreaRatio()))
                        .toList());
        outcome.fail(PipelineTaskErrorCode.RATIO_BELOW_THRESHOLD.name(),
                String.format("成功单元占比 %.2f 低于门槛 %.2f（内容单元 %d，失败 %d）",
                        ratio, properties.getSuccessUnitRatio(), contentUnits, effectiveFailedUnits));
    }

    /** 扫描页去掉空白页：空白页无内容可恢复，不参与 OCR 覆盖判定。 */
    private Set<Integer> scannedPages(QualityInfo quality, Set<Integer> blankPages) {
        return quality.getScannedPages().stream()
                .filter(page -> !blankPages.contains(page))
                .collect(Collectors.toSet());
    }

    /** OCR 路已产出文字的页码集合（无 OCR 能力或元素无页码时为空）。 */
    private Set<Integer> ocrPages(ParseSource ocrSource) {
        if (NullUtil.isNull(ocrSource)) {
            return Set.of();
        }
        return ocrSource.getElements().stream()
                .map(ParseElement::getPage)
                .filter(NullUtil::isNotNull)
                .collect(Collectors.toSet());
    }
}
