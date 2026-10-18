package com.workbuddy.worker.chunking.impl;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.common.domain.chunk.Chunk;
import com.workbuddy.common.domain.chunk.ChunkOutcome;
import com.workbuddy.common.domain.chunk.ChunkSet;
import com.workbuddy.common.domain.preprocess.PreprocessView;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.common.domain.rules.PreprocessViewRules;
import com.workbuddy.common.domain.structure.UnifiedDocument;
import com.workbuddy.common.domain.structure.UnifiedElement;
import com.workbuddy.common.domain.task.StepLogInfo;
import com.workbuddy.common.enums.chunk.ChunkAlgorithm;
import com.workbuddy.common.enums.chunk.ChunkContentType;
import com.workbuddy.common.enums.chunk.ChunkKind;
import com.workbuddy.common.enums.chunk.ChunkRoute;
import com.workbuddy.common.enums.chunk.PipelineKey;
import com.workbuddy.common.enums.structure.UnifiedElementType;
import com.workbuddy.common.enums.task.PipelineTaskErrorCode;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.chunking.ChunkContext;
import com.workbuddy.worker.chunking.ChunkProperties;
import com.workbuddy.worker.chunking.ChunkerPort;
import com.workbuddy.worker.chunking.SliceContext;
import com.workbuddy.worker.chunking.slice.ChunkPostProcessor;
import com.workbuddy.worker.chunking.slice.SliceStrategy;
import com.workbuddy.worker.chunking.strategy.ChunkStrategy;
import com.workbuddy.worker.pipeline.StepLogHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 切片编排（模板方法模式）：固定流程骨架 + 可替换策略（registry 按 (路, 算法) 查找）。
 * 流程：剔除过滤 → 标题章节栈维护（标题不成片）→ 内容路由分派切片器（表格可并入正文流）→
 * 后置处理链（结构重叠→碎片合并→标题入正文）→ 父子层级 → 编号/token → ChunkSet 组装与状态判定。
 * 纯算法，不碰 DB/产物存储（落库回写由 biz ChunkTaskRunner 编排）；确定性执行（策略无状态，状态全在 SliceContext）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChunkPipeline implements ChunkerPort {

    private final SliceStrategyRegistry registry;
    private final FallbackSlicerRegistry fallbackRegistry;
    private final ChunkProperties properties;
    private final List<ChunkPostProcessor> postProcessors;

    /**
     * 执行切片：预计算 → 逐元素路由分派 → 后置处理 → 父子层级 → 编号/token → ChunkSet 组装与状态判定。
     * 返回 ChunkOutcome（ChunkSet + 子步骤 + 建议状态）。
     */
    /**
     * 切片环节入口：按策略路由切分元素并结算缓冲。
     *
     * @param context 环节上下文
     * @return 环节产出
     */
    @Override
    public ChunkOutcome chunk(ChunkContext context) {
        ChunkOutcome outcome = new ChunkOutcome();
        PreprocessView view = context.getView();
        if (NullUtil.isNull(view) || NullUtil.isNull(view.getElements()) || view.getElements().isEmpty()) {
            outcome.fail(PipelineTaskErrorCode.CHUNK_EMPTY.name(), "上游预处理视图无任何可切内容");
            return outcome;
        }

        ChunkStrategy strategy = context.getStrategy();
        if (NullUtil.isNull(strategy)) {
            outcome.fail(PipelineTaskErrorCode.CHUNK_FAILED.name(), "任务未带切片策略快照，无法切片");
            return outcome;
        }
        SliceContext sliceContext = buildSliceContext(context, strategy);
        log.info("===> ChunkPipeline 开始切片, fileResultId={}, elements={}, strategy={}",
                context.getFileResultId(), view.getElements().size(), strategy.fullVersion());
        Stats stats = new Stats();
        List<Section> sections = new ArrayList<>();
        List<Chunk> orphans = new ArrayList<>();
        boolean ruleFailure = routeElements(view, strategy, sliceContext, sections, orphans, stats, outcome);

        // 后置处理链（按章分别执行：结构重叠→碎片合并→标题入正文）
        for (Section section : sections) {
            applyPostProcessors(section.children, sliceContext);
        }
        applyPostProcessors(orphans, sliceContext);

        // 父子层级后置（父片向量化归向量化环节策略开关，默认只向量化子片）
        List<Chunk> ordered = new ArrayList<>();
        boolean parentChild = strategy.pipelineOn(PipelineKey.PARENT_CHILD);
        List<Section> sectionsWithParent = new ArrayList<>();
        long parentStartedNanos = System.nanoTime();
        if (parentChild) {
            for (Section section : sections) {
                if (section.children.isEmpty()) {
                    continue;
                }
                Chunk parent = buildParentChunk(section);
                section.parentChunk = parent;
                sectionsWithParent.add(section);
                ordered.add(parent);
                stats.parentMatched++;
                stats.parentLen += parent.getCharCount();
            }
        }
        stats.parentNanos = System.nanoTime() - parentStartedNanos;
        for (Section section : sections) {
            if (!section.children.isEmpty()) {
                ordered.addAll(section.children);
            }
        }
        ordered.addAll(orphans);

        if (ordered.isEmpty()) {
            outcome.fail(PipelineTaskErrorCode.CHUNK_EMPTY.name(), "无可切内容（元素均被剔除或无文本）");
            return outcome;
        }

        // 编号与 Token 估算
        int index = 1;
        for (Chunk chunk : ordered) {
            chunk.setChunkId(String.format("chunk-%04d", index));
            chunk.setOrder(index++);
            chunk.setTokenCount((int) Math.ceil(chunk.getCharCount() / properties.getTokenDivisor()));
        }

        // 父片关联（编号完成后挂 parentChunkId，指向已编号父片）
        for (Section section : sectionsWithParent) {
            for (Chunk child : section.children) {
                child.setParentChunkId(section.parentChunk.getChunkId());
            }
        }

        ChunkSet chunkSet = assembleChunkSet(context, strategy, ordered);
        outcome.setChunkSet(chunkSet);
        outcome.setStepLogs(buildStepLogs(stats, strategy));
        outcome.setSuggestedStatus(ruleFailure
                ? PipelineTaskStatus.PARTIAL_SUCCESS : PipelineTaskStatus.SUCCESS);
        log.info("===> ChunkPipeline 切片完成, fileResultId={}, chunkCount={}, status={}, warningCount={}",
                context.getFileResultId(), ordered.size(), outcome.getSuggestedStatus().name(),
                outcome.getWarnings().size());
        return outcome;
    }

    /** 逐元素路由分派：标题切章、内容按策略路由到对应切片器，单元素异常隔离为告警 */
    private boolean routeElements(PreprocessView view, ChunkStrategy strategy, SliceContext sliceContext,
                                  List<Section> sections, List<Chunk> orphans, Stats stats, ChunkOutcome outcome) {
        boolean ruleFailure = false;
        Deque<String> titleStack = new ArrayDeque<>();
        Section current = null;
        ChunkAlgorithm bodyAlgorithm = strategy.routeAlgorithm(ChunkRoute.BODY);

        long routeStartedNanos = System.nanoTime();
        for (ViewElement element : view.getElements()) {
            String status = element.getStatus();
            if (NullUtil.isNotNull(status) && PreprocessViewRules.CHUNK_SKIP_STATUSES.contains(status)) {
                continue;
            }
            stats.routeMatched++;
            String type = element.getType();
            if (UnifiedElementType.TITLE.name().equals(type)) {
                // 标题只作章节边界与 titlePath，不单独成片
                flushBodyStrategy(sliceContext, current, orphans, stats);
                String titleText = element.getNormalizedText();
                updateTitleStack(titleStack, titleText, levelOf(sliceContext, element.getElementId()), strategy);
                current = new Section(String.join(" > ", titleStack));
                sections.add(current);
                sliceContext.setTitlePath(new ArrayList<>(titleStack));
                continue;
            }
            ChunkKind kind = route(type);
            // 表格并入正文流：表格不再独立路由，Markdown 化后作为正文流内联单元交给正文算法
            boolean inlineTable = kind == ChunkKind.TABLE && strategy.pipelineOn(PipelineKey.TABLE_IN_BODY_FLOW);
            if (inlineTable) {
                kind = ChunkKind.BODY;
            }
            if (kind != ChunkKind.BODY && NullUtil.isNotNull(bodyAlgorithm) && bodyAlgorithm.flushOnContentBoundary()) {
                // 正文聚合以表格/图片为界结算（正文组保持连续文本，不跨表格/图片合并）
                flushBodyStrategy(sliceContext, current, orphans, stats);
            }
            try {
                if (inlineTable) {
                    dispatchInlineTable(element, sliceContext, current, orphans, stats);
                } else {
                    SliceStrategy slicer = registry.get(routeAlgorithmOf(strategy, kind));
                    long startedNanos = System.nanoTime();
                    List<Chunk> chunks = slicer.slice(element, sliceContext);
                    stats.addNanos(kind, System.nanoTime() - startedNanos);
                    fillTitlePath(chunks, sliceContext);
                    attachToSection(current, orphans, chunks);
                    stats.incr(kind, chunks);
                    // 前导段落维护：每遇非空 BODY 元素更新（表+引导段落算法用）
                    if (kind == ChunkKind.BODY && StrUtil.isNotBlank(element.getNormalizedText())) {
                        sliceContext.setLeadParagraph(element.getNormalizedText());
                    }
                }
            } catch (Exception e) {
                ruleFailure = true;
                stats.warn(kind);
                outcome.getWarnings().add(kind.name() + " 切片器: " + element.getElementId() + ": "
                        + StrUtil.maxLength(String.valueOf(e.getMessage()), 100));
                log.warn("切片器异常, kind={}, elementId={}", kind, element.getElementId(), e);
            }
        }
        stats.routeNanos = System.nanoTime() - routeStartedNanos;
        flushBodyStrategy(sliceContext, current, orphans, stats);
        return ruleFailure;
    }

    // ---------------- 切片分派 ----------------

    /** 内容类型 → 切片路：TABLE→表格路；IMAGE→图片路；其余文本元素→正文路。
     *  兜底不是路由目标——它是正文路超长元素的降级切片模式（元素类型为封闭枚举，规则固定）。 */
    private ChunkKind route(String elementType) {
        if (UnifiedElementType.TABLE.name().equals(elementType)) {
            return ChunkKind.TABLE;
        }
        if (UnifiedElementType.IMAGE.name().equals(elementType)) {
            return ChunkKind.IMAGE;
        }
        return ChunkKind.BODY;
    }

    /** 表格并入正文流：用所选表格算法切片出合法 Markdown 片，逐片作为合成正文元素交给正文算法 */
    private void dispatchInlineTable(ViewElement tableElement, SliceContext context,
                                     Section section, List<Chunk> orphans, Stats stats) {
        ChunkStrategy strategy = context.getStrategy();
        SliceStrategy tableStrategy = registry.get(strategy.routeAlgorithm(ChunkRoute.TABLE));
        SliceStrategy bodyStrategy = registry.get(strategy.routeAlgorithm(ChunkRoute.BODY));
        long tableStartedNanos = System.nanoTime();
        List<Chunk> tableChunks = tableStrategy.slice(tableElement, context);
        stats.addNanos(ChunkKind.TABLE, System.nanoTime() - tableStartedNanos);
        for (Chunk tableChunk : tableChunks) {
            ViewElement inline = new ViewElement();
            inline.setElementId(tableElement.getElementId());
            inline.setType(UnifiedElementType.TABLE.name());
            inline.setPage(tableElement.getPage());
            inline.setStatus(tableElement.getStatus());
            inline.setNormalizedText(tableChunk.getContent());
            long bodyStartedNanos = System.nanoTime();
            List<Chunk> chunks = bodyStrategy.slice(inline, context);
            stats.addNanos(ChunkKind.BODY, System.nanoTime() - bodyStartedNanos);
            fillTitlePath(chunks, context);
            attachToSection(section, orphans, chunks);
            stats.incr(ChunkKind.BODY, chunks);
        }
    }

    /** 结算正文缓冲（TITLE 边界与文档末尾调用；产出跨元素聚合的残余片） */
    private void flushBodyStrategy(SliceContext context, Section section, List<Chunk> orphans, Stats stats) {
        SliceStrategy slicer = registry.get(context.getStrategy().routeAlgorithm(ChunkRoute.BODY));
        long startedNanos = System.nanoTime();
        List<Chunk> chunks = slicer.flush(context);
        stats.addNanos(ChunkKind.BODY, System.nanoTime() - startedNanos);
        fillTitlePath(chunks, context);
        attachToSection(section, orphans, chunks);
        stats.incr(ChunkKind.BODY, chunks);
    }

    private void applyPostProcessors(List<Chunk> chunks, SliceContext context) {
        for (ChunkPostProcessor processor : postProcessors) {
            processor.process(chunks, context);
        }
    }

    private void fillTitlePath(List<Chunk> chunks, SliceContext context) {
        for (Chunk chunk : chunks) {
            if (StrUtil.isBlank(chunk.getTitlePath())) {
                chunk.setTitlePath(String.join(" > ", context.getTitlePath()));
            }
        }
    }

    private ChunkAlgorithm routeAlgorithmOf(ChunkStrategy strategy, ChunkKind kind) {
        return switch (kind) {
            case BODY -> strategy.routeAlgorithm(ChunkRoute.BODY);
            case TABLE -> strategy.routeAlgorithm(ChunkRoute.TABLE);
            case IMAGE -> strategy.routeAlgorithm(ChunkRoute.IMAGE);
        };
    }

    private void attachToSection(Section section, List<Chunk> orphans, List<Chunk> chunks) {
        if (NullUtil.isNotNull(section)) {
            section.children.addAll(chunks);
        } else {
            orphans.addAll(chunks);
        }
    }

    // ---------------- 章节栈 ----------------

    private Integer levelOf(SliceContext ctx, String elementId) {
        UnifiedElement element = ctx.getById().get(elementId);
        return NullUtil.isNull(element) ? null : element.getLevel();
    }

    private void updateTitleStack(Deque<String> stack, String titleText, Integer level, ChunkStrategy strategy) {
        if (StrUtil.isBlank(titleText)) {
            return;
        }
        int targetLevel = NullUtil.isNull(level) ? 1 : Math.max(1, level);
        while (stack.size() >= targetLevel) {
            stack.pollLast();
        }
        stack.addLast(titleText);
        int maxLevel = strategy.pipelineInt(PipelineKey.TITLE_PATH_MAX_LEVEL, properties.getTitlePathMaxLevel());
        while (stack.size() > maxLevel) {
            stack.pollFirst();
        }
    }

    // ---------------- 父子层级 ----------------

    private Chunk buildParentChunk(Section section) {
        List<String> contents = section.children.stream().map(Chunk::getContent).toList();
        List<String> sourceIds = new ArrayList<>();
        Set<Integer> pages = new HashSet<>();
        for (Chunk child : section.children) {
            sourceIds.addAll(child.getSourceElementIds());
            if (NullUtil.isNotNull(child.getPageRange())) {
                pages.addAll(child.getPageRange());
            }
        }
        String content = String.join("\n", contents);
        Chunk parent = new Chunk();
        parent.setContent(content);
        parent.setContentType(ChunkContentType.SECTION.name());
        parent.setTitlePath(section.path);
        parent.setSourceElementIds(sourceIds);
        parent.setPageRange(pages.isEmpty() ? null : pages.stream().sorted().toList());
        parent.setCharCount(content.length());
        return parent;
    }

    // ---------------- 组装与统计 ----------------

    private SliceContext buildSliceContext(ChunkContext context, ChunkStrategy strategy) {
        SliceContext sliceContext = new SliceContext();
        sliceContext.setView(context.getView());
        sliceContext.setDocument(context.getDocument());
        sliceContext.setStrategy(strategy);
        sliceContext.setById(indexDocument(context.getDocument()));
        sliceContext.setFallback(fallbackRegistry.get(strategy.routeAlgorithm(ChunkRoute.FALLBACK)));
        return sliceContext;
    }

    private Map<String, UnifiedElement> indexDocument(UnifiedDocument document) {
        Map<String, UnifiedElement> byId = new HashMap<>();
        if (NullUtil.isNotNull(document) && NullUtil.isNotNull(document.getElements())) {
            for (UnifiedElement element : document.getElements()) {
                if (NullUtil.isNotNull(element.getId())) {
                    byId.put(element.getId(), element);
                }
            }
        }
        return byId;
    }

    private ChunkSet assembleChunkSet(ChunkContext context, ChunkStrategy strategy, List<Chunk> chunks) {
        ChunkSet chunkSet = new ChunkSet();
        chunkSet.setChunkSetId("cs-" + context.getView().getDocumentId() + "-" + strategy.fullVersion());
        chunkSet.setFileResultId(context.getFileResultId());
        chunkSet.setDocumentId(context.getView().getDocumentId());
        chunkSet.setStrategyVersion(strategy.fullVersion());
        chunkSet.setUpstreamProductRef(context.getUpstreamProductRef());
        chunkSet.setChunkCount(chunks.size());
        chunkSet.setChunks(chunks);
        return chunkSet;
    }

    private List<StepLogInfo> buildStepLogs(Stats stats, ChunkStrategy strategy) {
        List<StepLogInfo> logs = new ArrayList<>();
        logs.add(step("内容路由", "content-router-v1", stats.routeMatched, 0, 0, stats.routeNanos));
        logs.add(step("正文切片", capability(strategy.routeAlgorithm(ChunkRoute.BODY)),
                stats.bodyMatched, stats.bodyLen, stats.bodyWarnings, stats.bodyNanos));
        logs.add(step("表格切片", capability(strategy.routeAlgorithm(ChunkRoute.TABLE)),
                stats.tableMatched, stats.tableLen, stats.tableWarnings, stats.tableNanos));
        logs.add(step("图片切片", capability(strategy.routeAlgorithm(ChunkRoute.IMAGE)),
                stats.imageMatched, stats.imageLen, stats.imageWarnings, stats.imageNanos));
        // 兜底耗时恒 0：兜底在正文/表格切片器内部触发，不可单独计时（统计仍按 contentType=FALLBACK 计数）
        logs.add(step("兜底切片", capability(strategy.routeAlgorithm(ChunkRoute.FALLBACK)),
                stats.fallbackMatched, stats.fallbackLen, 0, 0));
        logs.add(step("父子关系补充", "parent-child-attach-v1", stats.parentMatched, stats.parentLen, 0,
                stats.parentNanos));
        return logs;
    }

    /** 子步骤 capability：由所选算法的枚举键推导（不带版本号；未识别回退 unknown） */
    private String capability(ChunkAlgorithm algorithm) {
        return NullUtil.isNull(algorithm) ? "unknown" : algorithm.key();
    }

    private StepLogInfo step(String stepName, String capability, int matched, int totalLen, int warnings,
                             long nanos) {
        return StepLogHelper.build(stepName, capability, matched, totalLen, warnings, nanos / 1_000_000);
    }

    /** 子步骤统计（产片数/总长/告警/各阶段耗时纳秒，平均长度=总长÷片数） */
    private static final class Stats {
        private int routeMatched;
        private int bodyMatched;
        private int bodyLen;
        private int bodyWarnings;
        private int tableMatched;
        private int tableLen;
        private int tableWarnings;
        private int imageMatched;
        private int imageLen;
        private int imageWarnings;
        private int fallbackMatched;
        private int fallbackLen;
        private int parentMatched;
        private int parentLen;
        private long routeNanos;
        private long bodyNanos;
        private long tableNanos;
        private long imageNanos;
        private long parentNanos;

        void addNanos(ChunkKind kind, long nanos) {
            switch (kind) {
                case BODY -> bodyNanos += nanos;
                case TABLE -> tableNanos += nanos;
                case IMAGE -> imageNanos += nanos;
                default -> {
                }
            }
        }

        void incr(ChunkKind kind, List<Chunk> chunks) {
            switch (kind) {
                case BODY -> {
                    for (Chunk chunk : chunks) {
                        if (ChunkContentType.FALLBACK.name().equals(chunk.getContentType())) {
                            fallbackMatched++;
                            fallbackLen += chunk.getCharCount();
                        } else {
                            bodyMatched++;
                            bodyLen += chunk.getCharCount();
                        }
                    }
                }
                case TABLE -> {
                    tableMatched += chunks.size();
                    tableLen += chunks.stream().mapToInt(Chunk::getCharCount).sum();
                }
                case IMAGE -> {
                    imageMatched += chunks.size();
                    imageLen += chunks.stream().mapToInt(Chunk::getCharCount).sum();
                }
                default -> {
                }
            }
        }

        void warn(ChunkKind kind) {
            switch (kind) {
                case BODY -> bodyWarnings++;
                case TABLE -> tableWarnings++;
                case IMAGE -> imageWarnings++;
                default -> {
                }
            }
        }
    }

    /** 章节容器：路径 + 子片 + 父片（编号完成后挂关联） */
    private static final class Section {
        private final String path;
        private final List<Chunk> children = new ArrayList<>();
        private Chunk parentChunk;

        Section(String path) {
            this.path = path;
        }
    }
}
