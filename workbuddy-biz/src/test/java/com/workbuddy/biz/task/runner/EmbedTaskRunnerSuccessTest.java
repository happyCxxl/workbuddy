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
import com.workbuddy.common.domain.embed.EmbedOutcome;
import com.workbuddy.common.domain.embed.EmbeddingSet;
import com.workbuddy.common.domain.entity.WbChunkSet;
import com.workbuddy.common.domain.entity.WbEmbeddingRecord;
import com.workbuddy.common.domain.entity.WbEmbeddingSet;
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.filecenter.provider.StorageRouter;
import com.workbuddy.filecenter.storage.FileStorage;
import com.workbuddy.worker.embedding.EmbedContext;
import com.workbuddy.worker.embedding.EmbedderPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collection;
import java.util.List;

import static com.workbuddy.biz.task.runner.EmbedTaskRunnerFixtures.SOURCE_ID;
import static com.workbuddy.biz.task.runner.EmbedTaskRunnerFixtures.UPSTREAM_TASK_ID;
import static com.workbuddy.biz.task.runner.EmbedTaskRunnerFixtures.chunkProduct;
import static com.workbuddy.biz.task.runner.EmbedTaskRunnerFixtures.chunkSet;
import static com.workbuddy.biz.task.runner.EmbedTaskRunnerFixtures.fileResult;
import static com.workbuddy.biz.task.runner.EmbedTaskRunnerFixtures.json;
import static com.workbuddy.biz.task.runner.EmbedTaskRunnerFixtures.queuedTask;
import static com.workbuddy.biz.task.runner.EmbedTaskRunnerFixtures.runner;
import static com.workbuddy.biz.task.runner.EmbedTaskRunnerFixtures.successOutcome;
import static com.workbuddy.biz.task.runner.EmbedTaskRunnerFixtures.upstreamTask;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 向量化任务执行器成功路径单测：落库链（EmbeddingSet 产物 + product + 两表分批 + 子步骤）/
 * 复用候选账本加载（cacheOn/Off）/ 部分成功 / 已完成任务跳过。
 */
@ExtendWith(MockitoExtension.class)
class EmbedTaskRunnerSuccessTest {

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
        runner = runner(pipelineTaskDbService, fileResultDbService, sourceFileDbService, pipelineProductDbService,
                stepLogDbService, chunkSetDbService, embeddingSetDbService, embeddingRecordDbService,
                fileStorage, storageRouter, embedder, indexSetService);
        // 上游产物的产出任务默认成功：门禁放行；不放行的状态由各用例自行改写（宽松桩）
        lenient().when(pipelineTaskDbService.getById(UPSTREAM_TASK_ID))
                .thenReturn(upstreamTask(PipelineTaskStatus.SUCCESS));
    }

    /** 领任务 + 文件结果 + 上游 CHUNK 产物（含切片归档读取）——所有成功路径共用 */
    private void stubClaimAndUpstream() {
        when(pipelineTaskDbService.getById(70L)).thenReturn(queuedTask());
        when(pipelineTaskDbService.claim(70L)).thenReturn(1);
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        when(pipelineProductDbService.getById(50L)).thenReturn(chunkProduct());
        when(fileStorage.getObject(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(), "artifacts", "abc".repeat(22))))
                .thenReturn(json(chunkSet()));
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
     * 守护 {@code corruptChunkCapabilitySnapshotShouldSkipWindowCheck}：该场景行为改动即失败。
     */
    @Test
    void corruptChunkCapabilitySnapshotShouldSkipWindowCheck() {
        // 上游切片产物行的策略快照坏：该次运行跳过窗口兼容校验（留空），不阻断向量化
        WbPipelineProduct product = chunkProduct();
        product.setCapabilitySnapshot("{oops");
        when(pipelineTaskDbService.getById(70L)).thenReturn(queuedTask());
        when(pipelineTaskDbService.claim(70L)).thenReturn(1);
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        when(pipelineProductDbService.getById(50L)).thenReturn(product);
        when(fileStorage.getObject(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(), "artifacts", "abc".repeat(22))))
                .thenReturn(json(chunkSet()));
        stubPersist();
        when(embedder.embed(any(EmbedContext.class))).thenReturn(successOutcome());

        runner.run(70L);

        ArgumentCaptor<EmbedContext> contextCaptor = ArgumentCaptor.forClass(EmbedContext.class);
        verify(embedder).embed(contextCaptor.capture());
        assertNull(contextCaptor.getValue().getChunkStrategy());
        verify(pipelineTaskDbService).finish(70L, PipelineTaskStatus.SUCCESS, null, null);
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
        when(fileStorage.getObject(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(),
                "artifacts", "hist".repeat(21))))
                .thenReturn(json(historySet));
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
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        when(pipelineProductDbService.getById(50L)).thenReturn(chunkProduct());
        when(fileStorage.getObject(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(), "artifacts", "abc".repeat(22))))
                .thenReturn(json(chunkSet()));
        stubPersist();
        when(embedder.embed(any(EmbedContext.class))).thenReturn(successOutcome());

        runner.run(70L);

        verify(embeddingSetDbService, never())
                .listHistoryByFileResultIdAndStrategyVersion(anyLong(), anyString(), eq(10));
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
        assertTrue(mockingDetails(embedder).getInvocations().stream()
                .noneMatch(inv -> "embed".equals(inv.getMethod().getName())));
    }
}
