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
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.enums.task.PipelineTaskErrorCode;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.convert.knowledge.EmbedConverterImpl;
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

import static com.workbuddy.biz.task.runner.EmbedTaskRunnerFixtures.SOURCE_ID;
import static com.workbuddy.biz.task.runner.EmbedTaskRunnerFixtures.UPSTREAM_TASK_ID;
import static com.workbuddy.biz.task.runner.EmbedTaskRunnerFixtures.chunkProduct;
import static com.workbuddy.biz.task.runner.EmbedTaskRunnerFixtures.chunkSet;
import static com.workbuddy.biz.task.runner.EmbedTaskRunnerFixtures.fileResult;
import static com.workbuddy.biz.task.runner.EmbedTaskRunnerFixtures.json;
import static com.workbuddy.biz.task.runner.EmbedTaskRunnerFixtures.queuedTask;
import static com.workbuddy.biz.task.runner.EmbedTaskRunnerFixtures.runner;
import static com.workbuddy.biz.task.runner.EmbedTaskRunnerFixtures.upstreamTask;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 向量化任务执行器失败路径单测：上游缺失/未成功、策略快照损坏、管线失败（不落产物）。
 * 用例失败即表示对应错误码或“未成功不读不落库”门禁行为被改动。
 */
@ExtendWith(MockitoExtension.class)
class EmbedTaskRunnerFailureTest {

    @Mock
    private WbPipelineTaskDbService pipelineTaskDbService;
    @Mock
    private WbFileResultDbService fileResultDbService;
    @Mock
    private WbPipelineProductDbService pipelineProductDbService;
    @Mock
    private FileStorage fileStorage;
    @Mock
    private EmbedderPort embedder;

    private EmbedTaskRunner runner;

    @BeforeEach
    void setUp() {
        // 本组只声明断言/桩涉及的 mock；构造器其余依赖用匿名 mock（默认为空链，不触发额外交互）
        runner = runner(pipelineTaskDbService, fileResultDbService, mock(WbSourceFileDbService.class),
                pipelineProductDbService, mock(WbPipelineStepLogDbService.class), mock(WbChunkSetDbService.class),
                mock(WbEmbeddingSetDbService.class), mock(WbEmbeddingRecordDbService.class), fileStorage,
                mock(StorageRouter.class), embedder, mock(IndexSetService.class), new EmbedConverterImpl());
        // 上游产物的产出任务默认成功：门禁放行；不放行的状态由各用例自行改写（宽松桩）
        lenient().when(pipelineTaskDbService.getById(UPSTREAM_TASK_ID))
                .thenReturn(upstreamTask(PipelineTaskStatus.SUCCESS));
    }

    /** 领任务 + 文件结果 + 上游 CHUNK 产物（含切片归档读取） */
    private void stubClaimAndUpstream() {
        when(pipelineTaskDbService.getById(70L)).thenReturn(queuedTask());
        when(pipelineTaskDbService.claim(70L)).thenReturn(1);
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        when(pipelineProductDbService.getById(50L)).thenReturn(chunkProduct());
        when(fileStorage.getObject(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(), "artifacts", "abc".repeat(22))))
                .thenReturn(json(chunkSet()));
    }

    /**
     * 守护 {@code missingUpstreamProductShouldFailTaskUpstreamMissing}：该场景行为改动即失败。
     */
    @Test
    void missingUpstreamProductShouldFailTaskUpstreamMissing() {
        when(pipelineTaskDbService.getById(70L)).thenReturn(queuedTask());
        when(pipelineTaskDbService.claim(70L)).thenReturn(1);
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());

        runner.run(70L);

        verify(pipelineTaskDbService).finish(70L, PipelineTaskStatus.FAILED,
                PipelineTaskErrorCode.TASK_UPSTREAM_MISSING.name(), "任务未指定上游切片产物");
        verify(fileStorage, never()).putObject(any(byte[].class));
        verify(embedder, never()).embed(any());
        assertTrue(mockingDetails(embedder).getInvocations().stream()
                .noneMatch(inv -> "embed".equals(inv.getMethod().getName())));
    }

    /**
     * 守护 {@code failedUpstreamTaskShouldFailEmbedFailed}：该场景行为改动即失败。
     */
    @Test
    void failedUpstreamTaskShouldFailEmbedFailed() {
        when(pipelineTaskDbService.getById(70L)).thenReturn(queuedTask());
        when(pipelineTaskDbService.claim(70L)).thenReturn(1);
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
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
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        when(pipelineProductDbService.getById(50L)).thenReturn(chunkProduct());

        runner.run(70L);

        verify(pipelineTaskDbService).finish(70L, PipelineTaskStatus.FAILED,
                PipelineTaskErrorCode.EMBED_FAILED.name(), "任务未带向量化策略快照或快照已损坏，无法向量化");
        // 守卫在读取上游产物本体之前：坏快照不读对象、不进管线
        verify(fileStorage, never()).getObject(any(ObjectRef.class));
        verify(embedder, never()).embed(any());
        assertTrue(mockingDetails(embedder).getInvocations().stream()
                .noneMatch(inv -> "embed".equals(inv.getMethod().getName())));
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
        assertTrue(mockingDetails(fileStorage).getInvocations().stream()
                .noneMatch(inv -> "putObject".equals(inv.getMethod().getName())));
    }
}
