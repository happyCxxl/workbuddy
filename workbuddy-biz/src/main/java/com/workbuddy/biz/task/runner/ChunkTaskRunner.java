package com.workbuddy.biz.task.runner;

import com.workbuddy.biz.service.db.WbChunkDbService;
import com.workbuddy.biz.service.db.WbChunkSetDbService;
import com.workbuddy.biz.service.db.WbFileResultDbService;
import com.workbuddy.biz.service.db.WbPipelineProductDbService;
import com.workbuddy.biz.service.db.WbPipelineTaskDbService;
import com.workbuddy.biz.task.runner.persistence.ProductPersistence;
import com.workbuddy.biz.task.runner.persistence.StepLogPersistence;
import com.workbuddy.biz.task.UpstreamProductGate;
import com.workbuddy.biz.task.support.TaskRunnerSupport;
import com.workbuddy.common.domain.chunk.Chunk;
import com.workbuddy.common.domain.chunk.ChunkOutcome;
import com.workbuddy.common.domain.chunk.ChunkSet;
import com.workbuddy.common.domain.entity.WbChunk;
import com.workbuddy.common.domain.entity.WbChunkSet;
import com.workbuddy.common.domain.entity.WbFileResult;
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.domain.preprocess.PreprocessView;
import com.workbuddy.common.domain.rules.ChunkRules;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.domain.structure.UnifiedDocument;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.enums.task.PipelineTaskErrorCode;
import com.workbuddy.common.enums.task.RowStatus;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.filecenter.service.FileStorage;
import com.workbuddy.worker.chunking.ChunkContext;
import com.workbuddy.worker.chunking.ChunkProperties;
import com.workbuddy.worker.chunking.ChunkerPort;
import com.workbuddy.worker.chunking.strategy.ChunkStrategy;
import com.workbuddy.worker.chunking.strategy.ChunkStrategyParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 切片任务执行器（消费循环调用）：领任务 → 读任务策略快照（触发时固定）→ 读最新 PREPROCESS 视图产物
 * （+ 其上游 STRUCTURE 统一文档作结构参照，缺失不阻断、titlePath/图注降级）→ 调 worker 切片管线 →
 * 写产物存储 + 落库（product/wb_knowledge_chunk_set/wb_knowledge_chunk 分批/6 条 step_log）→ 回写任务终态。
 *
 * @author cxxl
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChunkTaskRunner {

    private static final int BATCH_SIZE = 500;

    private final WbPipelineTaskDbService pipelineTaskDbService;
    private final WbFileResultDbService fileResultDbService;
    private final WbPipelineProductDbService pipelineProductDbService;
    private final WbChunkSetDbService chunkSetDbService;
    private final WbChunkDbService chunkDbService;
    private final FileStorage fileStorage;
    private final ChunkerPort chunker;
    private final ChunkProperties chunkProperties;
    private final ChunkStrategyParser strategyParser;
    private final StepLogPersistence stepLogPersistence;
    private final ProductPersistence productPersistence;
    private final UpstreamProductGate upstreamProductGate;

    /**
     * 执行单个切片任务（由消费循环提交，外层看门狗负责超时）。
     */
    public void run(Long taskId) {
        TaskRunnerSupport.Claimed claimed = TaskRunnerSupport.claimFileResult(pipelineTaskDbService,
                fileResultDbService, taskId, PipelineTaskErrorCode.CHUNK_FAILED, this::finishFailed);
        if (NullUtil.isNull(claimed)) {
            return;
        }
        WbPipelineTask task = claimed.task();
        WbFileResult fileResult = claimed.fileResult();
        log.info("===> ChunkTaskRunner 领取切片任务, taskId={}, fileResultId={}",
                taskId, task.getFileResultId());
        try {
            // 上游预处理视图产物：任务行缺引用与产出任务未成功都在助手里拒绝（门禁：未成功的运行不读也不落库）
            WbPipelineProduct preprocessProduct = TaskRunnerSupport.requireUpstream(pipelineProductDbService,
                    upstreamProductGate, task, taskId, ChunkRules.UPSTREAM_PREPROCESS_MISSING, this::finishFailed);
            if (NullUtil.isNull(preprocessProduct)) {
                return;
            }

            // 策略只来自策略版本表：任务行快照缺失或损坏即失败，不用代码里的默认策略兜底
            ChunkStrategy strategy = TaskRunnerSupport.parseStrategy(task, taskId, PipelineTaskErrorCode.CHUNK_FAILED,
                    "任务未带切片策略快照或快照已损坏，无法切片", strategyParser::parse, this::finishFailed);
            if (NullUtil.isNull(strategy)) {
                return;
            }
            PreprocessView view;
            try {
                byte[] content = fileStorage.getObject(ObjectRef.ofProduct(preprocessProduct));
                view = JsonUtil.toObject(new String(content, StandardCharsets.UTF_8), PreprocessView.class);
            } catch (Exception e) {
                log.warn("读取上游预处理视图产物失败, taskId={}, artifactId={}", taskId, preprocessProduct.getArtifactId(), e);
                finishFailed(taskId, PipelineTaskErrorCode.CHUNK_EMPTY.name(),
                        "上游预处理视图产物读取失败: " + e.getMessage());
                return;
            }
            if (NullUtil.isNull(view)) {
                log.warn("===> ChunkTaskRunner 切片失败：上游预处理视图产物反序列化失败, taskId={}, artifactId={}",
                        taskId, preprocessProduct.getArtifactId());
                finishFailed(taskId, PipelineTaskErrorCode.CHUNK_EMPTY.name(), "上游预处理视图产物反序列化失败");
                return;
            }
            UnifiedDocument document = readStructureDocument(preprocessProduct);

            ChunkContext context = new ChunkContext();
            context.setView(view);
            context.setDocument(document);
            context.setStrategy(strategy);
            context.setProperties(chunkProperties);
            context.setFileResultId(fileResult.getId());
            context.setUpstreamProductRef(preprocessProduct.getId());

            ChunkOutcome outcome = chunker.chunk(context);
            TaskRunnerSupport.complete(pipelineTaskDbService, stepLogPersistence, taskId, outcome,
                    () -> persistProduct(task, fileResult, preprocessProduct, strategy, outcome),
                    this::finishFailed);
        } catch (Exception e) {
            log.error("切片任务执行异常, taskId={}", taskId, e);
            finishFailed(taskId, TaskRunnerSupport.failureCode(e, PipelineTaskErrorCode.CHUNK_FAILED),
                    String.valueOf(e.getMessage()));
        }
    }

    /** 结构参照：PREPROCESS 产物上游的 STRUCTURE 产物（缺失不阻断，titlePath/图注降级） */
    private UnifiedDocument readStructureDocument(WbPipelineProduct preprocessProduct) {
        if (NullUtil.isNull(preprocessProduct.getUpstreamProductId())) {
            return null;
        }
        try {
            WbPipelineProduct structureProduct = pipelineProductDbService
                    .getById(preprocessProduct.getUpstreamProductId());
            if (NullUtil.isNull(structureProduct)) {
                return null;
            }
            byte[] content = fileStorage.getObject(ObjectRef.ofProduct(structureProduct));
            return JsonUtil.toObject(new String(content, StandardCharsets.UTF_8), UnifiedDocument.class);
        } catch (Exception e) {
            log.warn("读取结构参照产物失败（titlePath/图注降级）, fileResultId={}",
                    preprocessProduct.getFileResultId(), e);
            return null;
        }
    }

    /** 写产物存储 + product/wb_knowledge_chunk_set/wb_knowledge_chunk + 子步骤记录（成功/PARTIAL_SUCCESS 路径） */
    private void persistProduct(WbPipelineTask task, WbFileResult fileResult, WbPipelineProduct preprocessProduct,
                                ChunkStrategy strategy, ChunkOutcome outcome) {
        ChunkSet chunkSet = Objects.requireNonNull(outcome.getChunkSet(), "切片结果为空");
        WbPipelineProduct product = productPersistence.persist(task, PipelineStage.CHUNK,
                preprocessProduct.getId(), JsonUtil.toJsonStr(strategy), chunkSet);

        WbChunkSet chunkSetRow = new WbChunkSet();
        chunkSetRow.setFileResultId(fileResult.getId());
        chunkSetRow.setUpstreamProductId(preprocessProduct.getId());
        chunkSetRow.setChunkStrategyVersion(strategy.fullVersion());
        chunkSetRow.setChunkCount(chunkSet.getChunkCount());
        chunkSetRow.setTotalChars(chunkSet.getChunks().stream().mapToInt(Chunk::getCharCount).sum());
        chunkSetRow.setStatus(RowStatus.ACTIVE.name());
        chunkSetRow.setArtifactId(product.getArtifactId());
        chunkSetRow.setStorageType(product.getStorageType());
        chunkSetRow.setStorageSourceId(product.getStorageSourceId());
        chunkSetRow.setBucket(product.getBucket());
        chunkSetDbService.save(chunkSetRow);

        List<WbChunk> chunkRows = new ArrayList<>();
        for (Chunk chunk : chunkSet.getChunks()) {
            chunkRows.add(toKbChunk(chunkSetRow.getId(), strategy.fullVersion(), chunk));
        }
        chunkDbService.saveBatch(chunkRows, BATCH_SIZE);

        stepLogPersistence.save(task.getId(), outcome.getStepLogs());
        log.info("===> ChunkTaskRunner 切片完成, taskId={}, fileResultId={}, status={}, chunkCount={}, artifactId={}",
                task.getId(), fileResult.getId(), outcome.getSuggestedStatus().name(), chunkSet.getChunkCount(),
                product.getArtifactId());
    }

    private WbChunk toKbChunk(Long chunkSetId, String strategyVersion, Chunk chunk) {
        WbChunk row = new WbChunk();
        row.setChunkSetId(chunkSetId);
        row.setChunkId(chunk.getChunkId());
        row.setParentChunkId(chunk.getParentChunkId());
        row.setContent(chunk.getContent());
        row.setContentType(chunk.getContentType());
        row.setTitlePath(chunk.getTitlePath());
        // 溯源不落 MySQL：父片溯源会随章节大小无界增长（实测单父片 3503 字符，
        // 远超 wb_knowledge_chunk.source_element_ids 的容量），且检索链路用的是 Milvus 里的那份，
        // 该列曾是纯冗余副本。全量溯源只在产物与 Milvus 中保存。
        row.setPageRange(formatPageRange(chunk.getPageRange()));
        row.setTableRef(chunk.getTableRef());
        row.setOrderNo(chunk.getOrder());
        row.setCharCount(chunk.getCharCount());
        row.setTokenCount(chunk.getTokenCount());
        row.setStrategyVersion(strategyVersion);
        return row;
    }

    /** 页码范围落库：null → null；单页 "3"；连续 "1-3"；非连续压缩连续段逗号连接（无损等价，不截断） */
    static String formatPageRange(List<Integer> pages) {
        if (NullUtil.isNull(pages) || pages.isEmpty()) {
            return null;
        }
        List<Integer> distinct = pages.stream().distinct().sorted().toList();
        if (distinct.size() == 1) {
            return String.valueOf(distinct.getFirst());
        }
        StringBuilder sb = new StringBuilder();
        int start = distinct.getFirst();
        int prev = distinct.getFirst();
        for (int i = 1; i < distinct.size(); i++) {
            int cur = distinct.get(i);
            if (cur != prev + 1) {
                appendPageSegment(sb, start, prev);
                start = cur;
            }
            prev = cur;
        }
        appendPageSegment(sb, start, prev);
        return sb.toString();
    }

    /** 追加一段页码：单页 "3"，连续 "3-7"；非首段前置逗号 */
    private static void appendPageSegment(StringBuilder sb, int start, int end) {
        if (!sb.isEmpty()) {
            sb.append(',');
        }
        if (start == end) {
            sb.append(start);
        } else {
            sb.append(start).append('-').append(end);
        }
    }

    private void finishFailed(Long taskId, String errorCode, String errorMsg) {
        log.warn("===> ChunkTaskRunner 切片任务失败, taskId={}, errorCode={}, errorMsg={}",
                taskId, errorCode, errorMsg);
        TaskRunnerSupport.finishFailed(pipelineTaskDbService, taskId, errorCode, errorMsg);
    }
}
