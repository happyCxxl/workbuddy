package com.workbuddy.worker.parser;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.workbuddy.common.domain.parse.CapabilitySnapshot;
import com.workbuddy.common.domain.parse.ParseOutcome;
import com.workbuddy.common.domain.parse.ParseResult;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.domain.parse.ParseSummary;
import com.workbuddy.common.domain.parse.QualityInfo;
import com.workbuddy.common.domain.parse.capability.CapabilityUsage;
import com.workbuddy.common.domain.parse.signal.Signal;
import com.workbuddy.common.domain.task.StepLogInfo;
import com.workbuddy.common.enums.parse.ParseStepName;
import com.workbuddy.common.enums.parse.SignalSubtype;
import com.workbuddy.common.enums.task.PipelineTaskErrorCode;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.enums.task.StepStatus;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.filecenter.util.FileUtil;
import com.workbuddy.worker.parser.asset.ParseAssetException;
import com.workbuddy.worker.parser.capability.CapabilityRegistry;
import com.workbuddy.worker.parser.pdf.store.PageArtifactStoreException;
import com.workbuddy.worker.parser.signal.SignalDetector;
import com.workbuddy.worker.parser.signal.SignalFallbackHandler;
import com.workbuddy.worker.pipeline.StepLogHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.poi.EncryptedDocumentException;
import org.springframework.stereotype.Component;

import java.util.List;
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
    private final OcrFallbackCoordinator ocrFallbackCoordinator;
    private final ParseSummaryFactory parseSummaryFactory;
    private final ParseQualityGate parseQualityGate;

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
            ocrSource = ocrFallbackCoordinator.build(nativeSource, quality, context);
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
        parseQualityGate.evaluate(outcome, nativeSource, ocrSource, failedUnits, quality);
        // ⑥ 坏页比例达门槛时补一条维度汇总告警
        parseSummaryFactory.summarizeQuality(nativeSource, quality);
        ParseSummary parseSummary = parseSummaryFactory.build(outcome, nativeSource, ocrSource, quality);
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

}
