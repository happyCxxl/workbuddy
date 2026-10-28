package com.workbuddy.worker.chunking.impl;

import com.workbuddy.common.domain.chunk.Chunk;
import com.workbuddy.common.domain.chunk.ChunkOutcome;
import com.workbuddy.common.domain.chunk.ChunkSet;
import com.workbuddy.common.domain.preprocess.PreprocessView;
import com.workbuddy.common.domain.structure.UnifiedDocument;
import com.workbuddy.common.domain.structure.UnifiedElement;
import com.workbuddy.common.enums.chunk.ChunkRoute;
import com.workbuddy.common.enums.chunk.PipelineKey;
import com.workbuddy.common.enums.task.PipelineTaskErrorCode;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.chunking.ChunkContext;
import com.workbuddy.worker.chunking.ChunkProperties;
import com.workbuddy.worker.chunking.ChunkerPort;
import com.workbuddy.worker.chunking.SliceContext;
import com.workbuddy.worker.chunking.slice.ChunkPostProcessor;
import com.workbuddy.worker.chunking.strategy.ChunkStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 切片编排（模板方法模式）：固定流程骨架 + 可替换策略（registry 按 (路, 算法) 查找）。
 * 流程：剔除过滤 → 标题章节栈维护（标题不成片）→ 内容路由分派切片器（表格可并入正文流）→
 * 后置处理链（结构重叠→碎片合并→标题入正文）→ 父子层级 → 编号/token → ChunkSet 组装与状态判定。
 * 元素路由、章节装配与产物元数据分别由 {@link ChunkElementRouter}、{@link ChunkSectionAssembler}、
 * {@link ChunkMetadataSupport} 承担；本类保留主流程、上下文与步骤日志编排。
 * 纯算法，不碰 DB/产物存储（落库回写由 biz ChunkTaskRunner 编排）；确定性执行（策略无状态，状态全在 SliceContext）。
 */
@Slf4j
@Component
public class ChunkPipeline implements ChunkerPort {

    private final FallbackSlicerRegistry fallbackRegistry;
    private final ChunkProperties properties;
    private final ChunkElementRouter elementRouter;
    private final ChunkSectionAssembler sectionAssembler;

    /**
     * 构造切片管线。
     *
     * @param registry         切片器注册表
     * @param fallbackRegistry 兜底切片器注册表
     * @param properties       切片参数
     * @param postProcessors   后置处理器链（注册顺序即执行顺序）
     */
    public ChunkPipeline(SliceStrategyRegistry registry, FallbackSlicerRegistry fallbackRegistry,
                         ChunkProperties properties, List<ChunkPostProcessor> postProcessors) {
        this.fallbackRegistry = fallbackRegistry;
        this.properties = properties;
        this.sectionAssembler = new ChunkSectionAssembler(properties, postProcessors);
        this.elementRouter = new ChunkElementRouter(registry, sectionAssembler);
    }

    /**
     * 执行切片：预计算 → 逐元素路由分派 → 后置处理 → 父子层级 → 编号/token → ChunkSet 组装与状态判定。
     * 返回 ChunkOutcome（ChunkSet + 子步骤 + 建议状态）。
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
        ChunkMetadataSupport.Stats stats = new ChunkMetadataSupport.Stats();
        List<ChunkSectionAssembler.Section> sections = new ArrayList<>();
        List<Chunk> orphans = new ArrayList<>();
        boolean ruleFailure = elementRouter.routeElements(view, strategy, sliceContext, sections, orphans, stats,
                outcome);

        // 后置处理链（按章分别执行：结构重叠→碎片合并→标题入正文）
        sectionAssembler.postProcess(sections, orphans, sliceContext);

        // 父子层级后置（父片向量化归向量化环节策略开关，默认只向量化子片）
        List<Chunk> ordered = sectionAssembler.assembleHierarchy(sections, orphans, stats,
                strategy.pipelineOn(PipelineKey.PARENT_CHILD));
        if (ordered.isEmpty()) {
            outcome.fail(PipelineTaskErrorCode.CHUNK_EMPTY.name(), "无可切内容（元素均被剔除或无文本）");
            return outcome;
        }

        numberChunks(ordered);
        // 父片关联（编号完成后挂 parentChunkId，指向已编号父片）
        sectionAssembler.linkParentIds(sections);

        ChunkSet chunkSet = ChunkMetadataSupport.assembleChunkSet(context, strategy, ordered);
        outcome.setChunkSet(chunkSet);
        outcome.setStepLogs(ChunkMetadataSupport.buildStepLogs(stats, strategy));
        outcome.setSuggestedStatus(ruleFailure
                ? PipelineTaskStatus.PARTIAL_SUCCESS : PipelineTaskStatus.SUCCESS);
        log.info("===> ChunkPipeline 切片完成, fileResultId={}, chunkCount={}, status={}, warningCount={}",
                context.getFileResultId(), ordered.size(), outcome.getSuggestedStatus().name(),
                outcome.getWarnings().size());
        return outcome;
    }

    /** 编号与 Token 估算（文档顺序，chunkId 形如 chunk-0001） */
    private void numberChunks(List<Chunk> chunks) {
        int index = 1;
        for (Chunk chunk : chunks) {
            chunk.setChunkId(String.format("chunk-%04d", index));
            chunk.setOrder(index++);
            chunk.setTokenCount((int) Math.ceil(chunk.getCharCount() / properties.getTokenDivisor()));
        }
    }

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
}
