package com.workbuddy.worker.preprocessing.impl;
import com.workbuddy.common.enums.structure.ElementMark;
import com.workbuddy.common.enums.structure.PageMark;
import com.workbuddy.common.enums.structure.UnifiedElementType;
import com.workbuddy.common.enums.preprocess.ViewElementStatus;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.common.domain.task.StepLogInfo;
import com.workbuddy.common.domain.preprocess.PreprocessOutcome;
import com.workbuddy.common.domain.preprocess.PreprocessView;
import com.workbuddy.common.domain.preprocess.TraceEntry;
import com.workbuddy.common.domain.preprocess.ViewCell;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.common.domain.structure.UnifiedDocument;
import com.workbuddy.common.domain.structure.UnifiedElement;
import com.workbuddy.common.domain.structure.UnifiedPage;
import com.workbuddy.common.enums.preprocess.PreprocessParam;
import com.workbuddy.common.enums.preprocess.PreprocessRule;
import com.workbuddy.common.enums.structure.ConflictStatus;
import com.workbuddy.common.enums.task.PipelineTaskErrorCode;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.enums.task.StepStatus;
import com.workbuddy.common.enums.preprocess.CustomRuleAction;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.preprocessing.rule.CleanRule;
import com.workbuddy.worker.preprocessing.rule.CustomRuleMatcher;
import com.workbuddy.worker.preprocessing.PreprocessContext;
import com.workbuddy.worker.preprocessing.PreprocessorPort;
import com.workbuddy.worker.preprocessing.rule.RuleContext;
import com.workbuddy.worker.preprocessing.rule.RuleOutcome;
import com.workbuddy.worker.preprocessing.ViewElementHelper;
import com.workbuddy.worker.pipeline.StepLogHelper;
import com.workbuddy.worker.preprocessing.strategy.PreprocessCustomRule;
import com.workbuddy.worker.preprocessing.PreprocessProperties;
import com.workbuddy.worker.preprocessing.strategy.PreprocessRuleConfig;
import com.workbuddy.worker.preprocessing.strategy.PreprocessStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 预处理编排：预计算文档级集合 → 逐元素构建派生副本 → 按固定顺序执行启用规则
 * （逐元素 try/catch 隔离，异常只影响该规则产物）→ 派生视图组装 + 9 条子步骤（8 规则 + 视图组装）。
 * 纯算法，不碰 DB/产物存储（落库回写由 biz PreprocessTaskRunner 编排）；确定性执行。
 *
 * @author cxxl
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PreprocessPipeline implements PreprocessorPort {

    private final List<CleanRule> rules;
    private final PreprocessProperties properties;

    @Override
    public PreprocessOutcome preprocess(PreprocessContext context) {
        PreprocessOutcome outcome = new PreprocessOutcome();
        UnifiedDocument document = context.getDocument();
        PreprocessStrategy strategy = context.getStrategy();
        if (NullUtil.isNull(strategy)) {
            outcome.fail(PipelineTaskErrorCode.PREPROCESS_FAILED.name(), "任务未带预处理策略快照，无法预处理");
            return outcome;
        }
        if (NullUtil.isNull(document) || NullUtil.isNull(document.getElements()) || document.getElements().isEmpty()) {
            outcome.fail(PipelineTaskErrorCode.PREPROCESS_EMPTY.name(), "上游统一结构无任何可处理元素");
            return outcome;
        }
        log.info("===> PreprocessPipeline 开始预处理, fileResultId={}, elements={}, strategy={}",
                context.getFileResultId(), document.getElements().size(), strategy.fullVersion());

        RuleContext ruleContext = buildRuleContext(document, strategy);
        if (!ruleContext.getNoisePageNumbers().isEmpty()) {
            outcome.getWarnings().add("噪声页 " + ruleContext.getNoisePageNumbers().size() + " 页已标记");
        }

        List<CleanRule> ordered = rules.stream()
                .sorted(Comparator.comparingInt(CleanRule::order))
                .toList();
        Map<String, RuleStat> stats = new LinkedHashMap<>();
        boolean ruleFailure = false;
        List<ViewElement> viewElements = new ArrayList<>();

        for (UnifiedElement source : document.getElements()) {
            ViewElement view = buildViewElement(source);
            viewElements.add(view);

            if (ConflictStatus.BACKUP.name().equals(source.getConflictStatus())) {
                // 冲突被裁决方不进内容流（组装环节裁决，PRIMARY/BACKUP 并存）
                view.setStatus(ViewElementStatus.BACKUP_SKIPPED.name());
                view.setNormalizedText(null);
                ViewElementHelper.clearCellTexts(view);
                view.getPreprocessTrace().add(TraceEntry.of("backup-contract-v1", null,
                        TraceEntry.ACTION_EXCLUDE, null, null, "组装环节 BACKUP 元素不进 normalizedText 内容流"));
                continue;
            }
            if (UnifiedElementType.IMAGE.name().equals(source.getType())) {
                view.setStatus(ViewElementStatus.IMAGE_REF_ONLY.name());
                continue;
            }

            for (CleanRule rule : ordered) {
                if (!rule.enabledIn(strategy)) {
                    continue;
                }
                RuleStat stat = stats.computeIfAbsent(rule.stepName(), k -> new RuleStat());
                try {
                    long startedNanos = System.nanoTime();
                    RuleOutcome ruleOutcome = rule.apply(view, ruleContext);
                    stat.nanos += System.nanoTime() - startedNanos;
                    if (ruleOutcome.isMatched()) {
                        stat.matched++;
                        stat.changed += ruleOutcome.getChangedCount();
                        stat.warnings += countManualReview(ruleOutcome.getTraces());
                        view.getPreprocessTrace().addAll(ruleOutcome.getTraces());
                        view.getNormalizedFields().addAll(ruleOutcome.getFields());
                    }
                } catch (Exception e) {
                    ruleFailure = true;
                    stat.warnings++;
                    stat.error = StrUtil.maxLength(String.valueOf(e.getMessage()), 200);
                    outcome.getWarnings().add(rule.stepName() + ": " + view.getElementId() + ": "
                            + StrUtil.maxLength(String.valueOf(e.getMessage()), 100));
                    log.warn("预处理规则异常, rule={}, elementId={}", rule.name(), view.getElementId(), e);
                }
            }
        }

        long assemblyStartedNanos = System.nanoTime();
        PreprocessView view = assembleView(context, strategy, viewElements);
        long assemblyMillis = StepLogHelper.elapsedMillis(assemblyStartedNanos);
        outcome.setView(view);
        outcome.setStepLogs(buildStepLogs(ordered, stats, strategy, viewElements.size(),
                outcome.getWarnings().size(), assemblyMillis));
        outcome.setSuggestedStatus(ruleFailure
                ? PipelineTaskStatus.PARTIAL_SUCCESS : PipelineTaskStatus.SUCCESS);
        log.info("===> PreprocessPipeline 预处理完成, fileResultId={}, elements={}, status={}, warningCount={}",
                context.getFileResultId(), viewElements.size(), outcome.getSuggestedStatus().name(),
                outcome.getWarnings().size());
        return outcome;
    }

    // ---------------- 预计算 ----------------

    private RuleContext buildRuleContext(UnifiedDocument document, PreprocessStrategy strategy) {
        RuleContext context = new RuleContext();
        context.setDocument(document);
        context.setStrategy(strategy);
        context.setProperties(properties);

        Set<Integer> repeatedPages = new HashSet<>();
        Set<Integer> noisePages = new HashSet<>();
        if (NullUtil.isNotNull(document.getPages())) {
            for (UnifiedPage page : document.getPages()) {
                if (NullUtil.isNotNull(page.getMarks())) {
                    if (page.getMarks().contains(PageMark.REPEATED_PAGE.name())) {
                        repeatedPages.add(page.getPageNumber());
                    }
                    if (page.getMarks().contains(PageMark.NOISE_PAGE.name())) {
                        noisePages.add(page.getPageNumber());
                    }
                }
            }
        }
        context.setRepeatedPageNumbers(repeatedPages);
        context.setNoisePageNumbers(noisePages);

        Map<Integer, Integer> tocCount = new HashMap<>();
        Set<String> tocRunElementIds = new HashSet<>();
        List<String> tocRun = new ArrayList<>();
        for (UnifiedElement element : document.getElements()) {
            boolean tocLine = NullUtil.isNotNull(element.getMarks())
                    && element.getMarks().contains(ElementMark.TOC_LINE.name());
            if (NullUtil.isNotNull(element.getPage())) {
                if (tocLine) {
                    tocCount.merge(element.getPage(), 1, Integer::sum);
                }
                continue;
            }
            // Word 无页概念：按顺序维护连续 TOC_LINE 窗口
            if (tocLine) {
                tocRun.add(element.getId());
            } else {
                flushTocRun(tocRun, tocRunElementIds, strategy);
                tocRun.clear();
            }
        }
        flushTocRun(tocRun, tocRunElementIds, strategy);
        context.setTocLineCountByPage(tocCount);
        context.setTocRunElementIds(tocRunElementIds);

        // 自定义规则：预编译一次（非法正则剔除——保存校验已拦截，此处防御）
        List<CustomRuleMatcher> matchers = new ArrayList<>();
        for (PreprocessCustomRule rule : strategy.customRules()) {
            try {
                matchers.add(new CustomRuleMatcher(Pattern.compile(rule.getPattern()),
                        CustomRuleAction.of(rule.getAction()), rule.getReplacement(), rule.getPattern()));
            } catch (Exception e) {
                log.warn("自定义规则正则编译失败，跳过, pattern={}", rule.getPattern());
            }
        }
        context.setCustomRuleMatchers(matchers);
        return context;
    }

    private void flushTocRun(List<String> run, Set<String> accepted, PreprocessStrategy strategy) {
        int runMinLength = strategy.intParam(PreprocessRule.TOC, PreprocessParam.TOC_RUN_MIN_LENGTH,
                properties.getTocRunMinLength());
        if (run.size() >= runMinLength) {
            accepted.addAll(run);
        }
    }

    // ---------------- 视图构建 ----------------

    private ViewElement buildViewElement(UnifiedElement source) {
        ViewElement view = new ViewElement();
        view.setElementId(source.getId());
        view.setType(source.getType());
        view.setStatus(ViewElementStatus.NORMAL.name());
        view.setMarks(source.getMarks());
        view.setPage(source.getPage());
        view.setRawText(source.getText());
        view.setProvenance(source.getProvenance());
        if (NullUtil.isNotNull(source.getCells()) && !source.getCells().isEmpty()) {
            List<ViewCell> cells = new ArrayList<>();
            for (UnifiedElement cellSource : source.getCells()) {
                ViewCell cell = new ViewCell();
                cell.setCellId(cellSource.getId());
                cell.setText(cellSource.getText());
                cell.setRow(cellSource.getRow());
                cell.setCol(cellSource.getCol());
                cell.setIsHeader(cellSource.getIsHeader());
                cells.add(cell);
            }
            view.setCells(cells);
        }
        return view;
    }

    private PreprocessView assembleView(PreprocessContext context, PreprocessStrategy strategy,
                                        List<ViewElement> viewElements) {
        PreprocessView view = new PreprocessView();
        String documentId = NullUtil.isNull(context.getDocument().getDocumentInfo()) ? null
                : context.getDocument().getDocumentInfo().getDocumentId();
        view.setViewId("pv-" + documentId + "-" + strategy.fullVersion());
        view.setDocumentId(documentId);
        view.setFileResultId(context.getFileResultId());
        view.setSourceFileRef(NullUtil.isNull(context.getDocument().getDocumentInfo()) ? null
                : context.getDocument().getDocumentInfo().getSourceFileRef());
        view.setUpstreamProductRef(context.getUpstreamProductRef());
        view.setStrategyVersion(strategy.fullVersion());
        view.setOptions(flattenOptions(strategy));
        view.setElements(viewElements);
        return view;
    }

    /** 策略规则 → 扁平 options 展示（动作/开关 + 参数以 rule.key 前缀；自定义规则记条数） */
    private Map<String, String> flattenOptions(PreprocessStrategy strategy) {
        Map<String, String> options = new HashMap<>();
        for (PreprocessRule rule : PreprocessRule.values()) {
            PreprocessRuleConfig config = strategy.rule(rule);
            if (NullUtil.isNull(config)) {
                continue;
            }
            if (StrUtil.isNotBlank(config.getAction())) {
                options.put(rule.key(), config.getAction());
            } else if (StrUtil.isNotBlank(config.getEnabled())) {
                // 旧开关格式（仅迁移期可能出现）只作兜底：有 action 时以 action 为准，同一规则不留两份口径
                options.put(rule.key(), config.getEnabled());
            }
            if (NullUtil.isNotNull(config.getParams())) {
                config.getParams().forEach((key, value) -> options.put(rule.key() + "." + key, value));
            }
        }
        List<PreprocessCustomRule> customRules = strategy.customRules();
        if (!customRules.isEmpty()) {
            options.put("customRules", String.valueOf(customRules.size()));
        }
        return options;
    }

    // ---------------- 子步骤统计 ----------------

    private List<StepLogInfo> buildStepLogs(List<CleanRule> ordered, Map<String, RuleStat> stats,
                                            PreprocessStrategy strategy, int elementCount, int totalWarnings,
                                            long assemblyMillis) {
        List<StepLogInfo> stepLogs = new ArrayList<>();
        for (CleanRule rule : ordered) {
            RuleStat stat = stats.getOrDefault(rule.stepName(), new RuleStat());
            stepLogs.add(toStepLog(rule.stepName(), rule.name(), stat));
        }
        StepLogInfo assembly = StepLogHelper.build("视图组装", strategy.fullVersion(), elementCount, 0,
                totalWarnings, assemblyMillis);
        stepLogs.add(assembly);
        return stepLogs;
    }

    private StepLogInfo toStepLog(String stepName, String ruleId, RuleStat stat) {
        StepLogInfo step = StepLogHelper.begin(stepName);
        step.setStatus(StepStatus.SUCCESS.name());
        step.setCapabilityVersion(ruleId);
        step.setFinishedAt(LocalDateTime.now());
        step.setDuration((int) (stat.nanos / 1_000_000));
        step.setMatchedCount(stat.matched);
        step.setChangedCount(stat.changed);
        step.setWarningCount(stat.warnings);
        step.setError(stat.error);
        return step;
    }

    private int countManualReview(List<TraceEntry> traces) {
        int count = 0;
        for (TraceEntry trace : traces) {
            if (TraceEntry.ACTION_MANUAL_REVIEW.equals(trace.getAction())) {
                count++;
            }
        }
        return count;
    }

    /** 单规则统计（命中数/变更数/告警数/首错摘要/累计耗时纳秒） */
    private static final class RuleStat {
        private int matched;
        private int changed;
        private int warnings;
        private String error;
        private long nanos;
    }
}
