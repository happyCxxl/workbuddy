package com.workbuddy.biz.task.runner;

import com.workbuddy.biz.service.IndexSetService;
import com.workbuddy.biz.service.db.WbChunkSetDbService;
import com.workbuddy.biz.service.db.WbEmbeddingRecordDbService;
import com.workbuddy.biz.service.db.WbEmbeddingSetDbService;
import com.workbuddy.biz.service.db.WbFileResultDbService;
import com.workbuddy.biz.service.db.WbPipelineProductDbService;
import com.workbuddy.biz.service.db.WbPipelineStepLogDbService;
import com.workbuddy.biz.service.db.WbPipelineTaskDbService;
import com.workbuddy.biz.service.db.WbSourceFileDbService;
import com.workbuddy.biz.service.support.access.ChainStorageSupport;
import com.workbuddy.biz.task.runner.persistence.ProductPersistence;
import com.workbuddy.biz.task.runner.persistence.StepLogPersistence;
import com.workbuddy.biz.task.UpstreamProductGate;
import com.workbuddy.common.domain.chunk.Chunk;
import com.workbuddy.common.domain.chunk.ChunkSet;
import com.workbuddy.common.domain.embed.EmbedOutcome;
import com.workbuddy.common.domain.embed.EmbeddingRecord;
import com.workbuddy.common.domain.embed.EmbeddingSet;
import com.workbuddy.common.domain.entity.WbFileResult;
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.domain.task.StepLogInfo;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.filecenter.provider.StorageRouter;
import com.workbuddy.filecenter.storage.FileStorage;
import com.workbuddy.worker.chunking.ChunkProperties;
import com.workbuddy.worker.chunking.strategy.ChunkStrategyParser;
import com.workbuddy.worker.embedding.EmbedProperties;
import com.workbuddy.worker.embedding.EmbedderPort;
import com.workbuddy.worker.embedding.strategy.EmbedStrategyParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 向量化任务执行器测试夹具：构造执行器与任务/产物/输出样例。 */
final class EmbedTaskRunnerFixtures {

    /** 档案与产物所在的数据源 */
    static final Long SOURCE_ID = 1L;

    /** 上游产物的产出任务 ID（门禁按它的状态判定） */
    static final Long UPSTREAM_TASK_ID = 40L;

    private EmbedTaskRunnerFixtures() {
    }

    static EmbedTaskRunner runner(WbPipelineTaskDbService pipelineTaskDbService,
                                  WbFileResultDbService fileResultDbService,
                                  WbSourceFileDbService sourceFileDbService,
                                  WbPipelineProductDbService pipelineProductDbService,
                                  WbPipelineStepLogDbService stepLogDbService,
                                  WbChunkSetDbService chunkSetDbService,
                                  WbEmbeddingSetDbService embeddingSetDbService,
                                  WbEmbeddingRecordDbService embeddingRecordDbService,
                                  FileStorage fileStorage,
                                  StorageRouter storageRouter,
                                  EmbedderPort embedder,
                                  IndexSetService indexSetService) {
        EmbedProperties embedProperties = new EmbedProperties();
        // 存储守卫用真实实例 + mock 依赖：链类型取不到（默认 mock 返回 null）时不拦
        ChainStorageSupport chainStorage = new ChainStorageSupport(fileResultDbService, sourceFileDbService,
                fileStorage, storageRouter);
        return new EmbedTaskRunner(pipelineTaskDbService, fileResultDbService,
                pipelineProductDbService, chunkSetDbService, embeddingSetDbService,
                embeddingRecordDbService, fileStorage, indexSetService, embedder,
                new EmbedStrategyParser(embedProperties),
                new ChunkStrategyParser(new ChunkProperties()),
                embedProperties, new ChunkProperties(),
                new StepLogPersistence(stepLogDbService),
                new ProductPersistence(pipelineProductDbService, pipelineTaskDbService, chainStorage, fileStorage),
                new UpstreamProductGate(pipelineTaskDbService));
    }

    static WbPipelineTask upstreamTask(PipelineTaskStatus status) {
        WbPipelineTask task = new WbPipelineTask();
        task.setId(UPSTREAM_TASK_ID);
        task.setFileResultId(10L);
        task.setStage(PipelineStage.CHUNK.name());
        task.setStatus(status.name());
        return task;
    }

    static WbPipelineTask queuedTask() {
        WbPipelineTask task = new WbPipelineTask();
        task.setId(70L);
        task.setFileResultId(10L);
        // 上游产物按任务行上的显式引用取（触发时确定）
        task.setUpstreamProductId(50L);
        // 策略快照按任务行上的快照取（触发时固定）：触发时写入的是带 type/name/version 的完整快照
        task.setStrategySnapshot(
                "{\"type\":\"EMBED\",\"name\":\"embed-default\",\"version\":\"v1\",\"model\":\"text-embedding-v4\","
                        + "\"cacheEnabled\":\"ON\"}");
        task.setStage(PipelineStage.EMBED.name());
        task.setStatus(PipelineTaskStatus.QUEUED.name());
        return task;
    }

    static WbPipelineProduct chunkProduct() {
        WbPipelineProduct product = new WbPipelineProduct();
        product.setId(50L);
        product.setTaskId(UPSTREAM_TASK_ID);
        product.setArtifactId("abc".repeat(22));
        product.setStorageType(StorageType.MINIO.getCode());
        product.setStorageSourceId(SOURCE_ID);
        product.setBucket("artifacts");
        return product;
    }

    static WbFileResult fileResult() {
        WbFileResult fileResult = new WbFileResult();
        fileResult.setId(10L);
        fileResult.setSourceFileId(1L);
        return fileResult;
    }

    static ChunkSet chunkSet() {
        ChunkSet chunkSet = new ChunkSet();
        chunkSet.setChunkSetId("cs-doc-10-chunk-hybrid-v1");
        chunkSet.setFileResultId(10L);
        Chunk chunk = new Chunk();
        chunk.setChunkId("chunk-0001");
        chunk.setContent("正文片");
        chunk.setContentType("PARAGRAPH");
        chunk.setCharCount(3);
        chunkSet.setChunks(new ArrayList<>(List.of(chunk)));
        chunkSet.setChunkCount(1);
        return chunkSet;
    }

    static byte[] json(Object value) {
        return Objects.requireNonNull(JsonUtil.toJsonStr(value)).getBytes(StandardCharsets.UTF_8);
    }

    static EmbedOutcome successOutcome() {
        EmbedOutcome outcome = new EmbedOutcome();
        outcome.setSuggestedStatus(PipelineTaskStatus.SUCCESS);
        EmbeddingSet set = new EmbeddingSet();
        set.setEmbeddingSetId("es-cs-doc-10-embed-default-v1");
        set.setFileResultId(10L);
        set.setChunkSetRef(60L);
        set.setChunkSetId("cs-doc-10-chunk-hybrid-v1");
        set.setStrategyVersion("embed-default-v1");
        set.setModel("text-embedding-v4");
        set.setDimension(1024);
        set.setMetric("COSINE");
        set.setNormalized(true);
        set.setRecordCount(2);
        set.setCachedCount(1);
        EmbeddingRecord cached = new EmbeddingRecord();
        cached.setEmbeddingId("emb-0001");
        cached.setChunkId("chunk-0001");
        cached.setContentType("PARAGRAPH");
        cached.setInputText("正文片");
        cached.setInputTextHash("hash-1");
        cached.setTokenCount(2);
        cached.setStatus("CACHED");
        cached.setCacheHit(true);
        cached.setVector(List.of(1.0f));
        EmbeddingRecord fresh = new EmbeddingRecord();
        fresh.setEmbeddingId("emb-0002");
        fresh.setChunkId("chunk-0002");
        fresh.setContentType("PARAGRAPH");
        fresh.setInputText("新片");
        fresh.setInputTextHash("hash-2");
        fresh.setTokenCount(2);
        fresh.setStatus("SUCCESS");
        fresh.setRequestId("req-1");
        set.setRecords(List.of(cached, fresh));
        outcome.setEmbeddingSet(set);
        StepLogInfo step = new StepLogInfo();
        step.setStepName("复用判定");
        step.setStatus("SUCCESS");
        step.setCapabilityVersion("ledger-reuse-v1");
        step.setMatchedCount(1);
        outcome.setStepLogs(List.of(step));
        return outcome;
    }
}
