package com.workbuddy.worker.structure.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.workbuddy.common.domain.parse.PageDimension;
import com.workbuddy.common.domain.parse.ParseResult;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.domain.parse.Provenance;
import com.workbuddy.common.domain.parse.QualityWarning;
import com.workbuddy.common.domain.structure.AssembleOutcome;
import com.workbuddy.common.domain.structure.AssembleReport;
import com.workbuddy.common.domain.structure.DocumentInfo;
import com.workbuddy.common.domain.structure.DocumentQuality;
import com.workbuddy.common.domain.structure.DocumentRelation;
import com.workbuddy.common.domain.structure.UnifiedDocument;
import com.workbuddy.common.domain.structure.UnifiedElement;
import com.workbuddy.common.domain.structure.UnifiedPage;
import com.workbuddy.common.domain.task.StepLogInfo;
import com.workbuddy.common.enums.parse.QualityWarningCode;
import com.workbuddy.common.enums.structure.StructureStepName;
import com.workbuddy.common.enums.structure.UnifiedElementType;
import com.workbuddy.common.enums.task.PipelineTaskErrorCode;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.enums.task.StepStatus;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.pipeline.StepLogHelper;
import com.workbuddy.worker.structure.AssembleContext;
import com.workbuddy.worker.structure.DocumentAssemblerPort;
import com.workbuddy.worker.structure.craft.ContinuationOutcome;
import com.workbuddy.worker.structure.craft.DedupMerger;
import com.workbuddy.worker.structure.craft.ElementNormalizer;
import com.workbuddy.worker.structure.craft.MarkOutcome;
import com.workbuddy.worker.structure.craft.MergeOutcome;
import com.workbuddy.worker.structure.craft.NormalizeOutcome;
import com.workbuddy.worker.structure.craft.ReadingOrderResolver;
import com.workbuddy.worker.structure.craft.RepeatNoiseMarker;
import com.workbuddy.worker.structure.craft.StructureAssembler;
import com.workbuddy.worker.structure.craft.TableContinuationResolver;
import com.workbuddy.worker.structure.craft.TreeOutcome;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 组装编排（模板方法骨架）：标准化 → 去重与阅读顺序 → 结构组装（标题/章节）→ 跨页接续 → 关系重建 →
 * 溯源校验 → 重复与噪声识别 → 质量与空树判定（只产出 SUCCESS 或 STRUCTURE_EMPTY）。
 * 纯算法，不碰 DB/产物存储（落库回写由 biz StructureTaskRunner 编排）。
 *
 * @author cxxl
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssemblerPipeline implements DocumentAssemblerPort {

    /** 页面尺寸单位（落 UnifiedPage.unit） */
    private static final String PAGE_UNIT = "pt";

    /** 页面坐标原点（落 UnifiedPage.origin） */
    private static final String PAGE_ORIGIN = "top-left";

    /** 噪声页告警里最多列出的样例条数（完整清单进日志） */
    private static final int NOISE_SAMPLE_CAP = 3;

    private final ElementNormalizer normalizer;
    private final DedupMerger merger;
    private final ReadingOrderResolver readingOrderResolver;
    private final StructureAssembler structureAssembler;
    private final TableContinuationResolver continuationResolver;
    private final RepeatNoiseMarker repeatNoiseMarker;

    @Override
    public AssembleOutcome assemble(ParseResult parseResult, AssembleContext context) {
        AssembleOutcome outcome = new AssembleOutcome();
        if (NullUtil.isNull(parseResult) || NullUtil.isNull(parseResult.getFile())) {
            outcome.fail(PipelineTaskErrorCode.STRUCTURE_UPSTREAM_UNREADABLE.name(), "上游解析产物缺失");
            return outcome;
        }
        context.setSourceFileType(parseResult.getFile().getMimeType());
        log.info("===> AssemblerPipeline 开始组装, taskId={}, fileResultId={}, mimeType={}",
                context.getTaskId(), context.getFileResultId(), context.getSourceFileType());

        // ① 元素标准化
        StepLogInfo normalizeLog = StepLogHelper.begin(StructureStepName.NORMALIZE.value());
        NormalizeOutcome normalize = normalizer.normalize(parseResult.getSources(), context);
        List<UnifiedElement> normalized = normalize.getElements();
        if (!normalize.getUnmappedTypeCounts().isEmpty()) {
            log.warn("===> AssemblerPipeline 元素标准化跳过未识别类型, taskId={}, counts={}",
                    context.getTaskId(), normalize.getUnmappedTypeCounts());
        }
        normalizeLog.setStatus(StepStatus.SUCCESS.name());
        StepLogHelper.finish(normalizeLog);
        outcome.getStepLogs().add(normalizeLog);

        // ② 去重与合并 + ③ 阅读顺序
        StepLogInfo orderLog = StepLogHelper.begin(StructureStepName.DEDUP_ORDER.value());
        MergeOutcome merged = merger.merge(normalized, context);
        List<UnifiedElement> ordered = readingOrderResolver.resolve(merged.getElements(), context);
        orderLog.setStatus(StepStatus.SUCCESS.name());
        StepLogHelper.finish(orderLog);
        outcome.getStepLogs().add(orderLog);

        // ④ 结构组装 + ⑤ 跨页接续
        StepLogInfo assembleLog = StepLogHelper.begin(StructureStepName.ASSEMBLE_CONTINUATION.value());
        TreeOutcome tree = structureAssembler.assembleTree(ordered, context);
        ContinuationOutcome continuation = continuationResolver.joinContinuations(tree.getElements(), context);
        assembleLog.setStatus(StepStatus.SUCCESS.name());
        StepLogHelper.finish(assembleLog);
        outcome.getStepLogs().add(assembleLog);

        // ⑥ 溯源校验 + 组装报告
        StepLogInfo verifyLog = StepLogHelper.begin(StructureStepName.VERIFY_PROVENANCE.value());
        AssembleReport report = buildReport(merged, tree, continuation);
        verifyProvenance(continuation.getElements(), report);
        verifyLog.setStatus(StepStatus.SUCCESS.name());
        StepLogHelper.finish(verifyLog);
        outcome.getStepLogs().add(verifyLog);

        // 关系按最终元素表重建（接续会移除元素，避免出现指向已删元素的边）
        List<DocumentRelation> relations = mergeRelations(
                structureAssembler.buildRelations(continuation.getElements(), context),
                continuation.getRelations());

        // 组装文档 + 质量
        UnifiedDocument document = buildDocument(parseResult, context, continuation.getElements(), relations);

        // 重复与噪声识别（组装环节识别写标记，处置环节读取）
        StepLogInfo markLog = StepLogHelper.begin(StructureStepName.MARK_REPEAT_NOISE.value());
        MarkOutcome markOutcome = repeatNoiseMarker.mark(document);
        if (NullUtil.isNotNull(markOutcome.getWarnings())) {
            for (String reason : markOutcome.getWarnings()) {
                log.warn("===> AssemblerPipeline 标记明细, taskId={}, reason={}", context.getTaskId(), reason);
            }
        }
        markLog.setStatus(StepStatus.SUCCESS.name());
        markLog.setWarningCount(markOutcome.getNoisePageCount());
        StepLogHelper.finish(markLog);
        outcome.getStepLogs().add(markLog);
        report.setRepeatPageCount(markOutcome.getRepeatPageCount());
        report.setRepeatSegmentCount(markOutcome.getRepeatSegmentCount());
        report.setNoisePageCount(markOutcome.getNoisePageCount());

        document.setQuality(buildQuality(merged, tree, continuation, report, normalize, markOutcome, context));
        outcome.setDocument(document);
        outcome.setReport(report);

        // ⑦ 完整/空树判定（组装不产出 PARTIAL_SUCCESS：无法挂树元素字段自上线起恒空，无消费方）
        if (document.getElements().isEmpty()) {
            String emptyMessage = normalize.getUnmappedTypeCounts().isEmpty() ? "无任何可组装元素（空树）"
                    : "无任何可组装元素（空树；其中 " + normalize.unmappedElementCount() + " 个元素因类型未识别被跳过）";
            outcome.fail(PipelineTaskErrorCode.STRUCTURE_EMPTY.name(), emptyMessage);
        } else {
            outcome.setSuggestedStatus(PipelineTaskStatus.SUCCESS);
        }
        log.info("===> AssemblerPipeline 组装完成, taskId={}, fileResultId={}, elements={}, status={}, warningCount={}",
                context.getTaskId(), context.getFileResultId(), document.getElements().size(),
                outcome.getSuggestedStatus().name(), document.getQuality().getWarnings().size());
        for (QualityWarning warning : document.getQuality().getWarnings()) {
            log.warn("===> AssemblerPipeline 组装告警, taskId={}, code={}, message={}",
                    context.getTaskId(), warning.getCode(), warning.getMessage());
        }
        return outcome;
    }

    private AssembleReport buildReport(MergeOutcome merged, TreeOutcome tree, ContinuationOutcome continuation) {
        AssembleReport report = new AssembleReport();
        report.setMergePairs(merged.getMergePairs());
        report.setConflictCount(merged.getConflicts().size());
        report.setTitleCount(tree.getTitleCount());
        report.setTitleCountByCascade(ObjectUtil.defaultIfNull(tree.getTitleCountByCascade(), new HashMap<>()));
        report.setTitleCandidateCount(tree.getTitleCandidateCount());
        report.setContinuationCount(continuation.getContinuationCount());
        return report;
    }

    /** 噪声页样例：最多 {@link #NOISE_SAMPLE_CAP} 条原因，超出以"等"收尾（完整清单在日志） */
    private String noiseSampleText(List<String> reasons) {
        if (NullUtil.isNull(reasons) || reasons.isEmpty()) {
            return "";
        }
        String sample = String.join("、", reasons.subList(0, Math.min(NOISE_SAMPLE_CAP, reasons.size())));
        return "；例：" + sample + (reasons.size() > NOISE_SAMPLE_CAP ? " 等" : "");
    }

    /** 溯源校验：结构性节点不计入分母；可回溯 = 元素自身或其子元素（表格单元格）带原文定位 */
    private void verifyProvenance(List<UnifiedElement> elements, AssembleReport report) {
        int total = 0;
        int traceable = 0;
        for (UnifiedElement element : elements) {
            if (isStructuralElement(element)) {
                continue;
            }
            total++;
            if (isTraceable(element)) {
                traceable++;
            }
        }
        report.setProvenanceScopeCount(total);
        report.setTraceableRatio(total > 0 ? (double) traceable / total : 0);
    }

    /** 可回溯：元素自身带原文定位，或其子元素（表格单元格）带定位 */
    private boolean isTraceable(UnifiedElement element) {
        if (hasProvenance(element.getProvenance())) {
            return true;
        }
        return NullUtil.isNotNull(element.getCells()) && element.getCells().stream()
                .anyMatch(cell -> hasProvenance(cell.getProvenance()));
    }

    /** 原文定位是否齐备（文件引用 + 定位路径） */
    private boolean hasProvenance(Provenance provenance) {
        return NullUtil.isNotNull(provenance) && StrUtil.isNotBlank(provenance.getFile())
                && StrUtil.isNotBlank(provenance.getPath());
    }

    /** 结构性节点：组装环节自造的节点（SECTION；将来的 DOCUMENT/PAGE 同理），没有原文定位 */
    private boolean isStructuralElement(UnifiedElement element) {
        return UnifiedElementType.SECTION.name().equals(element.getType());
    }

    private UnifiedDocument buildDocument(ParseResult parseResult, AssembleContext context,
                                          List<UnifiedElement> elements, List<DocumentRelation> relations) {
        UnifiedDocument document = new UnifiedDocument();
        DocumentInfo info = new DocumentInfo();
        info.setDocumentId("doc-" + context.getFileResultId());
        info.setSourceFileRef(parseResult.getFile().getFileId());
        info.setSourceFileType(parseResult.getFile().getMimeType());
        info.setParserRunId(parseResult.getResultId());
        info.setSchemaVersion(UnifiedDocument.SCHEMA_VERSION);
        info.setCapabilitySnapshot(parseResult.getCapabilitySnapshot());
        document.setDocumentInfo(info);
        document.setPages(buildPages(parseResult));
        document.setElements(elements);
        document.setRelations(relations);
        return document;
    }

    private List<UnifiedPage> buildPages(ParseResult parseResult) {
        List<UnifiedPage> pages = new ArrayList<>();
        if (NullUtil.isNull(parseResult.getSources())) {
            return pages;
        }
        for (ParseSource source : parseResult.getSources()) {
            if (NullUtil.isNull(source.getPageDimensions())) {
                continue;
            }
            for (PageDimension dimension : source.getPageDimensions()) {
                UnifiedPage page = new UnifiedPage();
                page.setPageId("pg-" + dimension.getPage());
                page.setPageNumber(dimension.getPage());
                page.setWidth(dimension.getWidth());
                page.setHeight(dimension.getHeight());
                page.setRotation(0d);
                page.setUnit(PAGE_UNIT);
                page.setOrigin(PAGE_ORIGIN);
                pages.add(page);
            }
            break; // 页面基准取首个含尺寸的路（native）
        }
        return pages;
    }

    private DocumentQuality buildQuality(MergeOutcome merged, TreeOutcome tree,
                                         ContinuationOutcome continuation, AssembleReport report,
                                         NormalizeOutcome normalize, MarkOutcome markOutcome,
                                         AssembleContext context) {
        DocumentQuality quality = new DocumentQuality();
        quality.setConflicts(ObjectUtil.defaultIfNull(merged.getConflicts(), new ArrayList<>()));
        if (tree.getTitleCandidateCount() > 0) {
            quality.getWarnings().add(QualityWarning.of(QualityWarningCode.TITLE_CANDIDATE, null, "WARN",
                    "标题候选 " + tree.getTitleCandidateCount() + " 处，已按正文处理（规则拿不准，可重跑或后续接入模型兜底）"));
        }
        if (continuation.getSuspectedCount() > 0) {
            quality.getWarnings().add(QualityWarning.of(QualityWarningCode.SUSPECTED_CONTINUATION, null, "WARN",
                    "疑似续表 " + continuation.getSuspectedCount() + " 处（放宽规则命中，默认接续 + 表头继承）"));
        }
        int coveragePercent = (int) Math.round(report.getTraceableRatio() * 100);
        int lowPercent = context.getProperties().getProvenanceLowPercent();
        if (coveragePercent < lowPercent) {
            int missingCount = report.getProvenanceScopeCount()
                    - (int) Math.round(report.getTraceableRatio() * report.getProvenanceScopeCount());
            quality.getWarnings().add(QualityWarning.of(QualityWarningCode.PROVENANCE_MISSING, null, "WARN",
                    String.format("溯源可回溯占比 %.2f（低于阈值 %d%%），%d 个元素缺原文定位",
                            report.getTraceableRatio(), lowPercent, missingCount)));
        }
        if (report.getNoisePageCount() > 0) {
            quality.getWarnings().add(QualityWarning.of(QualityWarningCode.NOISE_PAGE, null, "WARN",
                    "噪声页 " + report.getNoisePageCount() + " 页已标记（空白/纯图片/乱码；处置在预处理环节）"
                            + noiseSampleText(markOutcome.getWarnings())));
        }
        if (!normalize.getUnmappedTypeCounts().isEmpty()) {
            quality.getWarnings().add(QualityWarning.of(QualityWarningCode.ELEMENT_TYPE_UNMAPPED, null, "WARN",
                    "未识别的解析元素类型 " + normalize.getUnmappedTypeCounts().size() + " 类（"
                            + unmappedTypesText(normalize.getUnmappedTypeCounts()) + "），共 "
                            + normalize.unmappedElementCount() + " 个元素已跳过"));
        }
        return quality;
    }

    /** 未识别类型明细文本（类型: 个数，多个以顿号分隔） */
    private String unmappedTypesText(Map<String, Integer> counts) {
        List<String> parts = new ArrayList<>();
        counts.forEach((type, count) -> parts.add(type + ": " + count));
        return String.join("、", parts);
    }

    private List<DocumentRelation> mergeRelations(List<DocumentRelation> treeRelations,
                                                  List<DocumentRelation> continuationRelations) {
        List<DocumentRelation> all = new ArrayList<>(ObjectUtil.defaultIfNull(treeRelations, new ArrayList<>()));
        all.addAll(ObjectUtil.defaultIfNull(continuationRelations, new ArrayList<>()));
        return all;
    }
}
