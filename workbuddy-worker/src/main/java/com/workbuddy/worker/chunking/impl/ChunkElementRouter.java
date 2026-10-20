package com.workbuddy.worker.chunking.impl;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.common.domain.chunk.Chunk;
import com.workbuddy.common.domain.chunk.ChunkOutcome;
import com.workbuddy.common.domain.preprocess.PreprocessView;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.common.domain.rules.PreprocessViewRules;
import com.workbuddy.common.enums.chunk.ChunkAlgorithm;
import com.workbuddy.common.enums.chunk.ChunkKind;
import com.workbuddy.common.enums.chunk.ChunkRoute;
import com.workbuddy.common.enums.chunk.PipelineKey;
import com.workbuddy.common.enums.structure.UnifiedElementType;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.chunking.SliceContext;
import com.workbuddy.worker.chunking.slice.SliceStrategy;
import com.workbuddy.worker.chunking.strategy.ChunkStrategy;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * 切片元素路由：按元素类型分派正文/表格/图片切片器，维护标题章节分组、表格内联与正文缓冲结算。
 * 单个元素的切片异常隔离为告警，不影响其余元素产片。
 */
@Slf4j
final class ChunkElementRouter {

    private final SliceStrategyRegistry registry;
    private final ChunkSectionAssembler sectionAssembler;

    /**
     * 构造元素路由器。
     *
     * @param registry         切片器注册表
     * @param sectionAssembler 章节装配器
     */
    ChunkElementRouter(SliceStrategyRegistry registry, ChunkSectionAssembler sectionAssembler) {
        this.registry = registry;
        this.sectionAssembler = sectionAssembler;
    }

    /**
     * 逐元素路由分派：标题切章、内容按策略路由到对应切片器，单元素异常隔离为告警。
     *
     * @param view         预处理视图
     * @param strategy     切片策略快照
     * @param sliceContext 切片上下文
     * @param sections     章节列表（输出）
     * @param orphans      无章节归属的切片（输出）
     * @param stats        运行统计
     * @param outcome      切片输出（告警写入）
     * @return 是否发生过切片器异常（决定 PARTIAL_SUCCESS）
     */
    boolean routeElements(PreprocessView view, ChunkStrategy strategy, SliceContext sliceContext,
                          List<ChunkSectionAssembler.Section> sections, List<Chunk> orphans,
                          ChunkMetadataSupport.Stats stats, ChunkOutcome outcome) {
        boolean ruleFailure = false;
        Deque<String> titleStack = new ArrayDeque<>();
        ChunkSectionAssembler.Section current = null;
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
                current = sectionAssembler.openSection(titleStack, element, sliceContext, strategy);
                sections.add(current);
                continue;
            }
            ChunkKind kind = routeKind(type);
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
                    dispatch(element, kind, sliceContext, current, orphans, stats);
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

    /** 内容类型 → 切片路：TABLE→表格路；IMAGE→图片路；其余文本元素→正文路。
     *  兜底不是路由目标——它是正文路超长元素的降级切片模式（元素类型为封闭枚举，规则固定）。 */
    private ChunkKind routeKind(String elementType) {
        if (UnifiedElementType.TABLE.name().equals(elementType)) {
            return ChunkKind.TABLE;
        }
        if (UnifiedElementType.IMAGE.name().equals(elementType)) {
            return ChunkKind.IMAGE;
        }
        return ChunkKind.BODY;
    }

    private void dispatch(ViewElement element, ChunkKind kind, SliceContext sliceContext,
                          ChunkSectionAssembler.Section current, List<Chunk> orphans,
                          ChunkMetadataSupport.Stats stats) {
        SliceStrategy slicer = registry.get(routeAlgorithmOf(sliceContext.getStrategy(), kind));
        long startedNanos = System.nanoTime();
        List<Chunk> chunks = slicer.slice(element, sliceContext);
        stats.addNanos(kind, System.nanoTime() - startedNanos);
        collect(kind, chunks, current, orphans, sliceContext, stats);
        // 前导段落维护：每遇非空 BODY 元素更新（表+引导段落算法用）
        if (kind == ChunkKind.BODY && StrUtil.isNotBlank(element.getNormalizedText())) {
            sliceContext.setLeadParagraph(element.getNormalizedText());
        }
    }

    /** 表格并入正文流：用所选表格算法切片出合法 Markdown 片，逐片作为合成正文元素交给正文算法 */
    private void dispatchInlineTable(ViewElement tableElement, SliceContext context,
                                     ChunkSectionAssembler.Section section, List<Chunk> orphans,
                                     ChunkMetadataSupport.Stats stats) {
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
            collect(ChunkKind.BODY, chunks, section, orphans, context, stats);
        }
    }

    /** 结算正文缓冲（TITLE 边界与文档末尾调用；产出跨元素聚合的残余片） */
    private void flushBodyStrategy(SliceContext context, ChunkSectionAssembler.Section section,
                                   List<Chunk> orphans, ChunkMetadataSupport.Stats stats) {
        SliceStrategy slicer = registry.get(context.getStrategy().routeAlgorithm(ChunkRoute.BODY));
        long startedNanos = System.nanoTime();
        List<Chunk> chunks = slicer.flush(context);
        stats.addNanos(ChunkKind.BODY, System.nanoTime() - startedNanos);
        collect(ChunkKind.BODY, chunks, section, orphans, context, stats);
    }

    private void collect(ChunkKind kind, List<Chunk> chunks, ChunkSectionAssembler.Section section,
                         List<Chunk> orphans, SliceContext context, ChunkMetadataSupport.Stats stats) {
        ChunkMetadataSupport.fillTitlePath(chunks, context);
        sectionAssembler.attachToSection(section, orphans, chunks);
        stats.incr(kind, chunks);
    }

    private ChunkAlgorithm routeAlgorithmOf(ChunkStrategy strategy, ChunkKind kind) {
        return switch (kind) {
            case BODY -> strategy.routeAlgorithm(ChunkRoute.BODY);
            case TABLE -> strategy.routeAlgorithm(ChunkRoute.TABLE);
            case IMAGE -> strategy.routeAlgorithm(ChunkRoute.IMAGE);
        };
    }
}
