package com.workbuddy.biz.task.runner;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.biz.service.IndexSetService;
import com.workbuddy.biz.service.db.WbChunkSetDbService;
import com.workbuddy.biz.service.db.WbEmbeddingRecordDbService;
import com.workbuddy.biz.service.db.WbEmbeddingSetDbService;
import com.workbuddy.common.convert.knowledge.EmbedConverter;
import com.workbuddy.biz.service.db.WbFileResultDbService;
import com.workbuddy.biz.service.db.WbPipelineProductDbService;
import com.workbuddy.biz.service.db.WbPipelineTaskDbService;
import com.workbuddy.biz.task.runner.persistence.ProductPersistence;
import com.workbuddy.biz.task.runner.persistence.StepLogPersistence;
import com.workbuddy.biz.task.UpstreamProductGate;
import com.workbuddy.biz.task.support.TaskRunnerSupport;
import com.workbuddy.common.domain.chunk.ChunkSet;
import com.workbuddy.common.domain.embed.EmbedOutcome;
import com.workbuddy.common.domain.embed.EmbeddingRecord;
import com.workbuddy.common.domain.embed.EmbeddingSet;
import com.workbuddy.common.domain.entity.WbChunkSet;
import com.workbuddy.common.domain.entity.WbEmbeddingRecord;
import com.workbuddy.common.domain.entity.WbEmbeddingSet;
import com.workbuddy.common.domain.entity.WbFileResult;
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.enums.task.PipelineTaskErrorCode;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.filecenter.storage.FileStorage;
import com.workbuddy.worker.chunking.ChunkProperties;
import com.workbuddy.worker.chunking.strategy.ChunkStrategy;
import com.workbuddy.worker.chunking.strategy.ChunkStrategyParser;
import com.workbuddy.worker.embedding.EmbedContext;
import com.workbuddy.worker.embedding.EmbedProperties;
import com.workbuddy.worker.embedding.EmbedderPort;
import com.workbuddy.worker.embedding.strategy.EmbedStrategy;
import com.workbuddy.worker.embedding.strategy.EmbedStrategyParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 向量化任务执行器（消费循环调用）：领任务 → 读任务策略快照（触发时固定）→ 读最新 CHUNK 产物（ChunkSet）
 * → 加载复用候选账本（同文件同策略历史集合，新→旧，回溯上限内）→ 调 worker 向量化管线 →
 * 写产物存储 + 落库（product/wb_knowledge_embedding_set/wb_knowledge_embedding_record 分批/子步骤）→ 回写任务终态。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmbedTaskRunner {

    private static final int BATCH_SIZE = 500;

    private final WbPipelineTaskDbService pipelineTaskDbService;
    private final WbFileResultDbService fileResultDbService;
    private final WbPipelineProductDbService pipelineProductDbService;
    private final WbChunkSetDbService chunkSetDbService;
    private final WbEmbeddingSetDbService embeddingSetDbService;
    private final WbEmbeddingRecordDbService embeddingRecordDbService;
    private final FileStorage fileStorage;
    private final IndexSetService indexSetService;
    private final EmbedderPort embedder;
    private final EmbedStrategyParser strategyParser;
    private final ChunkStrategyParser chunkStrategyParser;
    private final EmbedProperties embedProperties;
    private final ChunkProperties chunkProperties;
    private final StepLogPersistence stepLogPersistence;
    private final ProductPersistence productPersistence;
    private final UpstreamProductGate upstreamProductGate;
    private final EmbedConverter embedConverter;

    /**
     * 执行单个向量化任务（由消费循环提交，外层看门狗负责超时）。
     *
     * @param taskId 任务 ID
     */
    public void run(Long taskId) {
        TaskRunnerSupport.Claimed claimed = TaskRunnerSupport.claimFileResult(pipelineTaskDbService,
                fileResultDbService, taskId, PipelineTaskErrorCode.EMBED_FAILED, this::finishFailed);
        if (NullUtil.isNull(claimed)) {
            return;
        }
        WbPipelineTask task = claimed.task();
        WbFileResult fileResult = claimed.fileResult();
        log.info("===> EmbedTaskRunner 领取向量化任务, taskId={}, fileResultId={}",
                taskId, task.getFileResultId());
        try {
            // 上游切片产物：只认任务行上的显式引用，缺引用按任务失败处理
            // 上游切片产物：任务行缺引用与产出任务未成功都在助手里拒绝（门禁：未成功的运行不读也不落库）
            WbPipelineProduct chunkProduct = TaskRunnerSupport.requireUpstream(pipelineProductDbService,
                    upstreamProductGate, task, taskId, "任务未指定上游切片产物", this::finishFailed);
            if (NullUtil.isNull(chunkProduct)) {
                return;
            }

            // 策略只来自策略版本表：任务行快照缺失或损坏即失败，不用代码里的默认策略兜底
            EmbedStrategy strategy = TaskRunnerSupport.parseStrategy(task, taskId, PipelineTaskErrorCode.EMBED_FAILED,
                    "任务未带向量化策略快照或快照已损坏，无法向量化", strategyParser::parse, this::finishFailed);
            if (NullUtil.isNull(strategy)) {
                return;
            }
            ChunkSet chunkSet;
            try {
                byte[] content = fileStorage.getObject(ObjectRef.ofProduct(chunkProduct));
                chunkSet = JsonUtil.toObject(new String(content, StandardCharsets.UTF_8), ChunkSet.class);
            } catch (Exception e) {
                log.warn("===> EmbedTaskRunner 读取上游切片产物失败, taskId={}, artifactId={}", taskId, chunkProduct.getArtifactId(), e);
                finishFailed(taskId, PipelineTaskErrorCode.EMBED_EMPTY.name(),
                        "上游切片产物读取失败: " + e.getMessage());
                return;
            }
            if (NullUtil.isNull(chunkSet) || chunkSet.getChunks().isEmpty()) {
                finishFailed(taskId, PipelineTaskErrorCode.EMBED_EMPTY.name(), "上游切片产物为空");
                return;
            }

            EmbedContext context = new EmbedContext();
            context.setChunkSet(chunkSet);
            context.setChunkSetRef(latestChunkSetRef(fileResult.getId()));
            context.setChunkStrategy(resolveChunkStrategy(chunkProduct.getCapabilitySnapshot()));
            context.setChunkProperties(chunkProperties);
            context.setStrategy(strategy);
            context.setProperties(embedProperties);
            context.setFileResultId(fileResult.getId());
            context.setReuseCandidates(loadReuseCandidates(fileResult.getId(), strategy, task.getId()));

            EmbedOutcome outcome = embedder.embed(context);
            TaskRunnerSupport.complete(pipelineTaskDbService, stepLogPersistence, taskId, outcome,
                    () -> {
                        persistProduct(task, fileResult, chunkProduct, strategy, outcome);
                        notifyIndexSet(fileResult.getId());
                    },
                    this::finishFailed);
        } catch (Exception e) {
            log.error("===> EmbedTaskRunner 向量化任务执行异常, taskId={}", taskId, e);
            finishFailed(taskId, TaskRunnerSupport.failureCode(e, PipelineTaskErrorCode.EMBED_FAILED),
                    String.valueOf(e.getMessage()));
        }
    }

    /** 复用候选账本：同文件同策略成功集合（新→旧，回溯上限）；账本文件读取失败跳过继续（复用是优化） */
    private List<EmbeddingSet> loadReuseCandidates(Long fileResultId, EmbedStrategy strategy, Long taskId) {
        if (!strategy.cacheOn()) {
            return List.of();
        }
        List<EmbeddingSet> candidates = new ArrayList<>();
        List<WbEmbeddingSet> rows = embeddingSetDbService.listHistoryByFileResultIdAndStrategyVersion(
                fileResultId, strategy.fullVersion(), embedProperties.getReuseBacktrackLimit());
        for (WbEmbeddingSet row : rows) {
            try {
                byte[] content = fileStorage.getObject(ObjectRef.ofEmbeddingSet(row));
                EmbeddingSet set = JsonUtil.toObject(new String(content, StandardCharsets.UTF_8), EmbeddingSet.class);
                if (NullUtil.isNotNull(set)) {
                    candidates.add(set);
                }
            } catch (Exception e) {
                log.warn("===> EmbedTaskRunner 复用候选账本读取失败，跳过回溯, taskId={}, embeddingSetId={}, artifactId={}",
                        taskId, row.getEmbeddingSetId(), row.getArtifactId(), e);
            }
        }
        return candidates;
    }

    private Long latestChunkSetRef(Long fileResultId) {
        WbChunkSet chunkSet = chunkSetDbService.getLatestByFileResultId(fileResultId);
        return NullUtil.isNull(chunkSet) ? null : chunkSet.getId();
    }

    /**
     * 上游切片产物的策略快照 → 切片策略对象（窗口兼容校验据此推算最大片长）。
     *
     * <p>快照缺失或损坏返回 null（不构造默认策略）：该次运行跳过窗口兼容校验，任务照跑。
     *
     * @param snapshot 切片产物行上的策略快照 JSON 文本，可空
     * @return 补全后的切片策略；缺失或损坏返回 null
     */
    private ChunkStrategy resolveChunkStrategy(String snapshot) {
        if (StrUtil.isBlank(snapshot)) {
            return null;
        }
        try {
            return chunkStrategyParser.parse(snapshot);
        } catch (Exception e) {
            log.warn("===> EmbedTaskRunner 上游切片策略快照缺失或损坏，跳过窗口兼容校验", e);
            return null;
        }
    }

    /** 写产物存储 + product/wb_knowledge_embedding_set/wb_knowledge_embedding_record + 子步骤记录（成功/PARTIAL_SUCCESS 路径） */
    private void persistProduct(WbPipelineTask task, WbFileResult fileResult, WbPipelineProduct chunkProduct,
                                EmbedStrategy strategy, EmbedOutcome outcome) {
        EmbeddingSet embeddingSet = Objects.requireNonNull(outcome.getEmbeddingSet(), "向量化结果为空");
        WbPipelineProduct product = productPersistence.persist(task, PipelineStage.EMBED,
                chunkProduct.getId(), JsonUtil.toJsonStr(strategy), embeddingSet);

        WbEmbeddingSet setRow = embedConverter.setRow(fileResult.getId(), embeddingSet,
                embeddingSet.getStrategyVersion(), ObjectRef.ofProduct(product));
        embeddingSetDbService.save(setRow);

        List<WbEmbeddingRecord> recordRows = new ArrayList<>();
        for (EmbeddingRecord record : embeddingSet.getRecords()) {
            recordRows.add(toKbRecord(setRow.getId(), record));
        }
        embeddingRecordDbService.saveBatch(recordRows, BATCH_SIZE);

        stepLogPersistence.save(task.getId(), outcome.getStepLogs());
        log.info("===> EmbedTaskRunner 向量化完成, taskId={}, fileResultId={}, status={}, recordCount={},"
                        + " cachedCount={}, artifactId={}",
                task.getId(), fileResult.getId(), outcome.getSuggestedStatus().name(),
                embeddingSet.getRecordCount(), embeddingSet.getCachedCount(), product.getArtifactId());
    }

    private WbEmbeddingRecord toKbRecord(Long embeddingSetId, EmbeddingRecord record) {
        WbEmbeddingRecord row = embedConverter.recordRow(embeddingSetId, record);
        row.setCacheHit(record.isCacheHit());
        return row;
    }

    private void finishFailed(Long taskId, String errorCode, String errorMsg) {
        log.warn("===> EmbedTaskRunner 向量化任务失败, taskId={}, errorCode={}, errorMsg={}",
                taskId, errorCode, errorMsg);
        TaskRunnerSupport.finishFailed(pipelineTaskDbService, taskId, errorCode, errorMsg);
    }

    /** 文件产物就绪 → 索引自动构建判定（失败不阻断向量化任务终态） */
    private void notifyIndexSet(Long fileResultId) {
        try {
            indexSetService.onFileProductsReady(fileResultId);
        } catch (Exception e) {
            log.warn("===> EmbedTaskRunner 索引自动构建回调异常, fileResultId={}", fileResultId, e);
        }
    }
}
