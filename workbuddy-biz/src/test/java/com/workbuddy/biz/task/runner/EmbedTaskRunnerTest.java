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
import com.workbuddy.biz.service.support.ChainStorageSupport;
import com.workbuddy.biz.task.runner.persistence.ProductPersistence;
import com.workbuddy.biz.task.runner.persistence.StepLogPersistence;
import com.workbuddy.biz.task.UpstreamProductGate;
import com.workbuddy.common.domain.chunk.Chunk;
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
import com.workbuddy.common.domain.task.StepLogInfo;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.enums.task.PipelineTaskErrorCode;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.filecenter.provider.StorageRouter;
import com.workbuddy.filecenter.storage.FileStorage;
import com.workbuddy.worker.chunking.ChunkProperties;
import com.workbuddy.worker.chunking.strategy.ChunkStrategyParser;
import com.workbuddy.worker.embedding.EmbedContext;
import com.workbuddy.worker.embedding.EmbedProperties;
import com.workbuddy.worker.embedding.EmbedderPort;
import com.workbuddy.worker.embedding.strategy.EmbedStrategyParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 向量化任务执行器单测：成功落库链（EmbeddingSet 产物 + product + 两表分批 + 子步骤）/
 * 上游切片产物缺失 / 管线失败 / 复用候选账本加载（cacheOn/Off）。
 */
@ExtendWith(MockitoExtension.class)
class EmbedTaskRunnerTest {

    /** 档案与产物所在的数据源 */
    private static final Long SOURCE_ID = 1L;

    /** 上游产物的产出任务 ID（门禁按它的状态判定） */
    private static final Long UPSTREAM_TASK_ID = 40L;

    @Mock
    private WbPipelineTaskDbService pipelineTaskDbService;
    @Mock
    private WbFileResultDbService fileResultDbService;
    @Mock
    private WbSourceFileDbService sourceFileDbService;
    @Mock
    private WbPipelineProductDbService pipelineProductDbService;
    @Mock
    private WbPipelineStepLogDbService stepLogDbService;
    @Mock
    private WbChunkSetDbService chunkSetDbService;
    @Mock
    private WbEmbeddingSetDbService embeddingSetDbService;
    @Mock
    private WbEmbeddingRecordDbService embeddingRecordDbService;
    @Mock
    private FileStorage fileStorage;
    @Mock
    private StorageRouter storageRouter;
    @Mock
    private EmbedderPort embedder;
    @Mock
    private IndexSetService indexSetService;

    private EmbedTaskRunner runner;

    @BeforeEach
    void setUp() {
        EmbedProperties embedProperties = new EmbedProperties();
        // 存储守卫用真实实例 + mock 依赖：链类型取不到（默认 mock 返回 null）时不拦
        ChainStorageSupport chainStorage = new ChainStorageSupport(fileResultDbService, sourceFileDbService,
                fileStorage, storageRouter);
        runner = new EmbedTaskRunner(pipelineTaskDbService, fileResultDbService,
                pipelineProductDbService, chunkSetDbService, embeddingSetDbService,
                embeddingRecordDbService, fileStorage, indexSetService, embedder,
                new EmbedStrategyParser(embedProperties),
                new ChunkStrategyParser(new ChunkProperties()),
                embedProperties, new ChunkProperties(),
                new StepLogPersistence(stepLogDbService),
                new ProductPersistence(pipelineProductDbService, pipelineTaskDbService, chainStorage, fileStorage),
                new UpstreamProductGate(pipelineTaskDbService));
        // 上游产物的产出任务默认成功：门禁放行；不放行的状态由各用例自行改写（宽松桩）
        lenient().when(pipelineTaskDbService.getById(UPSTREAM_TASK_ID)).thenReturn(upstreamTask(PipelineTaskStatus.SUCCESS));
    }

    /** 上游产物的产出任务（环节 CHUNK） */
    private WbPipelineTask upstreamTask(PipelineTaskStatus status) {
        WbPipelineTask task = new WbPipelineTask();
        task.setId(UPSTREAM_TASK_ID);
        task.setFileResultId(10L);
        task.setStage(PipelineStage.CHUNK.name());
        task.setStatus(status.name());
        return task;
    }

    private WbPipelineTask queuedTask() {
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

    private WbPipelineProduct chunkProduct() {
        WbPipelineProduct product = new WbPipelineProduct();
        product.setId(50L);
        product.setTaskId(UPSTREAM_TASK_ID);
        product.setArtifactId("abc".repeat(22));
        product.setStorageType(StorageType.MINIO.getCode());
        product.setStorageSourceId(SOURCE_ID);
        product.setBucket("artifacts");
        return product;
    }

    private ChunkSet chunkSet() {
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

    private EmbedOutcome successOutcome() {
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

    /** 领任务 + 文件结果 + 上游 CHUNK 产物（含切片归档读取）——所有成功/失败路径共用 */
    private void stubClaimAndUpstream() {
        when(pipelineTaskDbService.getById(70L)).thenReturn(queuedTask());
        when(pipelineTaskDbService.claim(70L)).thenReturn(1);
        WbFileResult fileResult = new WbFileResult();
        fileResult.setId(10L);
        fileResult.setSourceFileId(1L);
        when(fileResultDbService.getById(10L)).thenReturn(fileResult);
        when(pipelineProductDbService.getById(50L))
                .thenReturn(chunkProduct());
        when(fileStorage.getObject(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(), "artifacts", "abc".repeat(22))))
                .thenReturn(Objects.requireNonNull(JsonUtil.toJsonStr(chunkSet())).getBytes(StandardCharsets.UTF_8));
    }

    /** 成功落库链专有桩：切片集合引用 + 产物写回 + product 行 ID */
    private void stubPersist() {
        WbChunkSet chunkSetRow = new WbChunkSet();
        chunkSetRow.setId(60L);
        when(chunkSetDbService.getLatestByFileResultId(10L)).thenReturn(chunkSetRow);
        when(fileStorage.putObject(any(byte[].class)))
                .thenReturn(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(), "artifacts", "def".repeat(22)));
        when(pipelineProductDbService.save(any(WbPipelineProduct.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbPipelineProduct.class).setId(80L);
            return true;
        });
    }

    /**
     * 守护 {@code shouldPersistProductAndFinishSuccess}：该场景行为改动即失败。
     */
    @Test
    void shouldPersistProductAndFinishSuccess() {
        stubClaimAndUpstream();
        stubPersist();
        when(embedder.embed(any(EmbedContext.class))).thenReturn(successOutcome());

        runner.run(70L);

        ArgumentCaptor<WbPipelineProduct> productCaptor = ArgumentCaptor.forClass(WbPipelineProduct.class);
        verify(pipelineProductDbService).save(productCaptor.capture());
        assertEquals(PipelineStage.EMBED.name(), productCaptor.getValue().getStage());
        assertEquals(50L, productCaptor.getValue().getUpstreamProductId());
        verify(pipelineTaskDbService).updateProductId(eq(70L), anyLong());

        ArgumentCaptor<WbEmbeddingSet> setCaptor = ArgumentCaptor.forClass(WbEmbeddingSet.class);
        verify(embeddingSetDbService).save(setCaptor.capture());
        assertEquals("es-cs-doc-10-embed-default-v1", setCaptor.getValue().getEmbeddingSetId());
        assertEquals(1024, setCaptor.getValue().getDimension());
        assertEquals(1, setCaptor.getValue().getCachedCount());

        verify(embeddingRecordDbService).saveBatch(any(), eq(500));
        verify(stepLogDbService).save(any());
        verify(pipelineTaskDbService).finish(70L, PipelineTaskStatus.SUCCESS, null, null);
        verify(indexSetService).onFileProductsReady(10L);
    }

    /**
     * 守护 {@code missingUpstreamProductShouldFailTaskUpstreamMissing}：该场景行为改动即失败。
     */
    @Test
    void missingUpstreamProductShouldFailTaskUpstreamMissing() {
        when(pipelineTaskDbService.getById(70L)).thenReturn(queuedTask());
        when(pipelineTaskDbService.claim(70L)).thenReturn(1);
        WbFileResult fileResult = new WbFileResult();
        fileResult.setId(10L);
        when(fileResultDbService.getById(10L)).thenReturn(fileResult);

        runner.run(70L);

        verify(pipelineTaskDbService).finish(70L, PipelineTaskStatus.FAILED,
                PipelineTaskErrorCode.TASK_UPSTREAM_MISSING.name(), "任务未指定上游切片产物");
        verify(fileStorage, never()).putObject(any(byte[].class));
        verify(embedder, never()).embed(any());
        org.junit.jupiter.api.Assertions.assertTrue(org.mockito.Mockito.mockingDetails(embedder).getInvocations().stream()
                .noneMatch(inv -> "embed".equals(inv.getMethod().getName())));
    }

    /**
     * 守护 {@code failedUpstreamTaskShouldFailEmbedFailed}：该场景行为改动即失败。
     */
    @Test
    void failedUpstreamTaskShouldFailEmbedFailed() {
        when(pipelineTaskDbService.getById(70L)).thenReturn(queuedTask());
        when(pipelineTaskDbService.claim(70L)).thenReturn(1);
        WbFileResult fileResult = new WbFileResult();
        fileResult.setId(10L);
        when(fileResultDbService.getById(10L)).thenReturn(fileResult);
        when(pipelineProductDbService.getById(50L)).thenReturn(chunkProduct());
        // 上游产物所属任务已失败，产物本体却已落盘：门禁按任务状态拒绝继续
        when(pipelineTaskDbService.getById(UPSTREAM_TASK_ID)).thenReturn(upstreamTask(PipelineTaskStatus.FAILED));

        runner.run(70L);

        ArgumentCaptor<String> errorMsg = ArgumentCaptor.forClass(String.class);
        verify(pipelineTaskDbService).finish(eq(70L), eq(PipelineTaskStatus.FAILED),
                eq(PipelineTaskErrorCode.EMBED_FAILED.name()), errorMsg.capture());
        assertTrue(errorMsg.getValue().contains(PipelineTaskStatus.FAILED.name()), errorMsg.getValue());
        // 门禁在读取产物本体之前：不读对象、不进向量化管线
        verify(fileStorage, never()).getObject(any(ObjectRef.class));
        verify(embedder, never()).embed(any());
    }

    /**
     * 守护 {@code corruptStrategySnapshotShouldFailEmbedFailed}：该场景行为改动即失败。
     */
    @Test
    void corruptStrategySnapshotShouldFailEmbedFailed() {
        // 快照坏（"{oops"）：解析抛异常被显式接住，任务落 EMBED_FAILED 且文案干净可辨识
        WbPipelineTask task = queuedTask();
        task.setStrategySnapshot("{oops");
        when(pipelineTaskDbService.getById(70L)).thenReturn(task);
        when(pipelineTaskDbService.claim(70L)).thenReturn(1);
        WbFileResult fileResult = new WbFileResult();
        fileResult.setId(10L);
        when(fileResultDbService.getById(10L)).thenReturn(fileResult);
        when(pipelineProductDbService.getById(50L)).thenReturn(chunkProduct());

        runner.run(70L);

        verify(pipelineTaskDbService).finish(70L, PipelineTaskStatus.FAILED,
                PipelineTaskErrorCode.EMBED_FAILED.name(), "任务未带向量化策略快照或快照已损坏，无法向量化");
        // 守卫在读取上游产物本体之前：坏快照不读对象、不进管线
        verify(fileStorage, never()).getObject(any(ObjectRef.class));
        verify(embedder, never()).embed(any());
        org.junit.jupiter.api.Assertions.assertTrue(org.mockito.Mockito.mockingDetails(embedder).getInvocations().stream()
                .noneMatch(inv -> "embed".equals(inv.getMethod().getName())));
    }

    /**
     * 守护 {@code corruptChunkCapabilitySnapshotShouldSkipWindowCheck}：该场景行为改动即失败。
     */
    @Test
    void corruptChunkCapabilitySnapshotShouldSkipWindowCheck() {
        // 上游切片产物行的策略快照坏：该次运行跳过窗口兼容校验（留空），不阻断向量化
        WbPipelineProduct product = chunkProduct();
        product.setCapabilitySnapshot("{oops");
        when(pipelineTaskDbService.getById(70L)).thenReturn(queuedTask());
        when(pipelineTaskDbService.claim(70L)).thenReturn(1);
        WbFileResult fileResult = new WbFileResult();
        fileResult.setId(10L);
        fileResult.setSourceFileId(1L);
        when(fileResultDbService.getById(10L)).thenReturn(fileResult);
        when(pipelineProductDbService.getById(50L)).thenReturn(product);
        when(fileStorage.getObject(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(), "artifacts", "abc".repeat(22))))
                .thenReturn(Objects.requireNonNull(JsonUtil.toJsonStr(chunkSet())).getBytes(StandardCharsets.UTF_8));
        stubPersist();
        when(embedder.embed(any(EmbedContext.class))).thenReturn(successOutcome());

        runner.run(70L);

        ArgumentCaptor<EmbedContext> contextCaptor = ArgumentCaptor.forClass(EmbedContext.class);
        verify(embedder).embed(contextCaptor.capture());
        assertNull(contextCaptor.getValue().getChunkStrategy());
        verify(pipelineTaskDbService).finish(70L, PipelineTaskStatus.SUCCESS, null, null);
    }

    /**
     * 守护 {@code pipelineFailureShouldFinishFailed}：该场景行为改动即失败。
     */
    @Test
    void pipelineFailureShouldFinishFailed() {
        stubClaimAndUpstream();
        EmbedOutcome failed = new EmbedOutcome();
        failed.setSuggestedStatus(PipelineTaskStatus.FAILED);
        failed.setErrorCode(PipelineTaskErrorCode.EMBED_CONSISTENCY_FAILED.name());
        failed.setErrorMsg("维度不一致");
        when(embedder.embed(any(EmbedContext.class))).thenReturn(failed);

        runner.run(70L);

        verify(pipelineTaskDbService).finish(70L, PipelineTaskStatus.FAILED,
                PipelineTaskErrorCode.EMBED_CONSISTENCY_FAILED.name(), "维度不一致");
        verify(fileStorage, never()).putObject(any(byte[].class));
        org.junit.jupiter.api.Assertions.assertTrue(org.mockito.Mockito.mockingDetails(fileStorage).getInvocations().stream()
                .noneMatch(inv -> "putObject".equals(inv.getMethod().getName())));
    }

    /**
     * 守护 {@code reuseCandidatesShouldBeLoadedWhenCacheOn}：该场景行为改动即失败。
     */
    @Test
    void reuseCandidatesShouldBeLoadedWhenCacheOn() {
        stubClaimAndUpstream();
        stubPersist();
        WbEmbeddingSet historyRow = new WbEmbeddingSet();
        historyRow.setEmbeddingSetId("es-old");
        historyRow.setArtifactId("hist".repeat(21));
        historyRow.setStorageType(StorageType.MINIO.getCode());
        historyRow.setStorageSourceId(SOURCE_ID);
        historyRow.setBucket("artifacts");
        when(embeddingSetDbService.listHistoryByFileResultIdAndStrategyVersion(10L, "embed-default-v1", 10))
                .thenReturn(List.of(historyRow));
        EmbeddingSet historySet = new EmbeddingSet();
        historySet.setEmbeddingSetId("es-old");
        when(fileStorage.getObject(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(), "artifacts", "hist".repeat(21))))
                .thenReturn(Objects.requireNonNull(JsonUtil.toJsonStr(historySet)).getBytes(StandardCharsets.UTF_8));
        when(embedder.embed(any(EmbedContext.class))).thenReturn(successOutcome());

        runner.run(70L);

        ArgumentCaptor<EmbedContext> contextCaptor = ArgumentCaptor.forClass(EmbedContext.class);
        verify(embedder).embed(contextCaptor.capture());
        assertEquals(1, contextCaptor.getValue().getReuseCandidates().size());
        assertEquals("es-old", contextCaptor.getValue().getReuseCandidates().getFirst().getEmbeddingSetId());
    }

    /**
     * 守护 {@code reuseCandidatesShouldNotBeLoadedWhenCacheOff}：该场景行为改动即失败。
     */
    @Test
    void reuseCandidatesShouldNotBeLoadedWhenCacheOff() {
        when(pipelineTaskDbService.getById(70L)).thenReturn(taskWithCacheOff());
        when(pipelineTaskDbService.claim(70L)).thenReturn(1);
        WbFileResult fileResult = new WbFileResult();
        fileResult.setId(10L);
        fileResult.setSourceFileId(1L);
        when(fileResultDbService.getById(10L)).thenReturn(fileResult);
        when(pipelineProductDbService.getById(50L))
                .thenReturn(chunkProduct());
        when(fileStorage.getObject(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(), "artifacts", "abc".repeat(22))))
                .thenReturn(Objects.requireNonNull(JsonUtil.toJsonStr(chunkSet())).getBytes(StandardCharsets.UTF_8));
        stubPersist();
        when(embedder.embed(any(EmbedContext.class))).thenReturn(successOutcome());

        runner.run(70L);

        verify(embeddingSetDbService, never()).listHistoryByFileResultIdAndStrategyVersion(anyLong(), anyString(), eq(10));
        ArgumentCaptor<EmbedContext> contextCaptor = ArgumentCaptor.forClass(EmbedContext.class);
        verify(embedder).embed(contextCaptor.capture());
        assertTrue(contextCaptor.getValue().getReuseCandidates().isEmpty());
    }

    private WbPipelineTask taskWithCacheOff() {
        WbPipelineTask task = queuedTask();
        task.setStrategySnapshot("{\"model\":\"text-embedding-v4\",\"cacheEnabled\":\"OFF\"}");
        return task;
    }

    /**
     * 守护 {@code persistShouldBatchRecordsAndFinishPartialSuccess}：该场景行为改动即失败。
     */
    @Test
    void persistShouldBatchRecordsAndFinishPartialSuccess() {
        stubClaimAndUpstream();
        stubPersist();
        EmbedOutcome outcome = successOutcome();
        outcome.setSuggestedStatus(PipelineTaskStatus.PARTIAL_SUCCESS);
        when(embedder.embed(any(EmbedContext.class))).thenReturn(outcome);

        runner.run(70L);

        verify(pipelineTaskDbService).finish(70L, PipelineTaskStatus.PARTIAL_SUCCESS, null, null);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<WbEmbeddingRecord>> batchCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(embeddingRecordDbService).saveBatch(batchCaptor.capture(), eq(500));
        assertEquals(2, batchCaptor.getValue().size());
    }

    /**
     * 守护 {@code finishedTaskShouldBeSkipped}：该场景行为改动即失败。
     */
    @Test
    void finishedTaskShouldBeSkipped() {
        WbPipelineTask finished = queuedTask();
        finished.setStatus(PipelineTaskStatus.SUCCESS.name());
        when(pipelineTaskDbService.getById(70L)).thenReturn(finished);

        runner.run(70L);

        verify(pipelineTaskDbService, never()).claim(anyLong());
        verify(embedder, never()).embed(any());
        org.junit.jupiter.api.Assertions.assertTrue(org.mockito.Mockito.mockingDetails(embedder).getInvocations().stream()
                .noneMatch(inv -> "embed".equals(inv.getMethod().getName())));
    }
}
