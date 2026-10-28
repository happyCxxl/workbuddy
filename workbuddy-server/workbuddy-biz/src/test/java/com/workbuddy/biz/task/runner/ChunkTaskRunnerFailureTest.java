package com.workbuddy.biz.task.runner;

import com.workbuddy.biz.service.db.WbChunkDbService;
import com.workbuddy.biz.service.db.WbChunkSetDbService;
import com.workbuddy.biz.service.db.WbFileResultDbService;
import com.workbuddy.biz.service.db.WbPipelineProductDbService;
import com.workbuddy.biz.service.db.WbPipelineStepLogDbService;
import com.workbuddy.biz.service.db.WbPipelineTaskDbService;
import com.workbuddy.biz.service.db.WbSourceFileDbService;
import com.workbuddy.common.domain.chunk.ChunkOutcome;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.enums.task.PipelineTaskErrorCode;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.filecenter.provider.StorageRouter;
import com.workbuddy.filecenter.storage.FileStorage;
import com.workbuddy.worker.chunking.ChunkContext;
import com.workbuddy.worker.chunking.ChunkerPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.workbuddy.biz.task.runner.ChunkTaskRunnerFixtures.SOURCE_ID;
import static com.workbuddy.biz.task.runner.ChunkTaskRunnerFixtures.UPSTREAM_TASK_ID;
import static com.workbuddy.biz.task.runner.ChunkTaskRunnerFixtures.fileResult;
import static com.workbuddy.biz.task.runner.ChunkTaskRunnerFixtures.json;
import static com.workbuddy.biz.task.runner.ChunkTaskRunnerFixtures.preprocessProduct;
import static com.workbuddy.biz.task.runner.ChunkTaskRunnerFixtures.queuedTask;
import static com.workbuddy.biz.task.runner.ChunkTaskRunnerFixtures.runner;
import static com.workbuddy.biz.task.runner.ChunkTaskRunnerFixtures.upstreamTask;
import static com.workbuddy.biz.task.runner.ChunkTaskRunnerFixtures.view;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 切片任务执行器失败路径单测：上游缺失/未成功、策略快照损坏或缺失、管线失败（不落产物）。
 * 用例失败即表示对应错误码或“未成功不读不落库”门禁行为被改动。
 */
@ExtendWith(MockitoExtension.class)
class ChunkTaskRunnerFailureTest {

    @Mock
    private WbPipelineTaskDbService pipelineTaskDbService;
    @Mock
    private WbFileResultDbService fileResultDbService;
    @Mock
    private WbPipelineProductDbService pipelineProductDbService;
    @Mock
    private WbChunkSetDbService chunkSetDbService;
    @Mock
    private WbChunkDbService chunkDbService;
    @Mock
    private FileStorage fileStorage;
    @Mock
    private ChunkerPort chunker;

    private ChunkTaskRunner runner;

    @BeforeEach
    void setUp() {
        // 本组只声明断言/桩涉及的 mock；构造器其余依赖用匿名 mock（默认为空链，不触发额外交互）
        runner = runner(pipelineTaskDbService, fileResultDbService, mock(WbSourceFileDbService.class),
                pipelineProductDbService, mock(WbPipelineStepLogDbService.class), chunkSetDbService, chunkDbService,
                fileStorage, mock(StorageRouter.class), chunker);
        // 上游产物的产出任务默认成功：门禁放行；不放行的状态由各用例自行改写（宽松桩）
        lenient().when(pipelineTaskDbService.getById(UPSTREAM_TASK_ID))
                .thenReturn(upstreamTask(PipelineTaskStatus.SUCCESS));
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

        verify(pipelineTaskDbService).finish(eq(70L), eq(PipelineTaskStatus.FAILED),
                eq(PipelineTaskErrorCode.TASK_UPSTREAM_MISSING.name()), any());
        verify(fileStorage, never()).putObject(any(byte[].class));
        assertTrue(mockingDetails(fileStorage).getInvocations().stream()
                .noneMatch(inv -> "putObject".equals(inv.getMethod().getName())));
    }

    /**
     * 守护 {@code failedUpstreamTaskShouldFailChunkFailed}：该场景行为改动即失败。
     */
    @Test
    void failedUpstreamTaskShouldFailChunkFailed() {
        when(pipelineTaskDbService.getById(70L)).thenReturn(queuedTask());
        when(pipelineTaskDbService.claim(70L)).thenReturn(1);
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        when(pipelineProductDbService.getById(50L)).thenReturn(preprocessProduct());
        // 上游产物所属任务已失败，产物本体却已落盘：门禁按任务状态拒绝继续
        when(pipelineTaskDbService.getById(UPSTREAM_TASK_ID)).thenReturn(upstreamTask(PipelineTaskStatus.FAILED));

        runner.run(70L);

        ArgumentCaptor<String> errorMsg = ArgumentCaptor.forClass(String.class);
        verify(pipelineTaskDbService).finish(eq(70L), eq(PipelineTaskStatus.FAILED),
                eq(PipelineTaskErrorCode.CHUNK_FAILED.name()), errorMsg.capture());
        assertTrue(errorMsg.getValue().contains(PipelineTaskStatus.FAILED.name()), errorMsg.getValue());
        // 门禁在读取产物本体之前：不读对象、不进切片管线
        verify(fileStorage, never()).getObject(any(ObjectRef.class));
        verify(chunker, never()).chunk(any());
    }

    /**
     * 守护 {@code corruptStrategySnapshotShouldFailChunkFailed}：该场景行为改动即失败。
     */
    @Test
    void corruptStrategySnapshotShouldFailChunkFailed() {
        // 快照坏（"{oops"）：解析抛异常被显式接住，任务落 CHUNK_FAILED 且文案干净可辨识
        WbPipelineTask task = queuedTask();
        task.setStrategySnapshot("{oops");
        when(pipelineTaskDbService.getById(70L)).thenReturn(task);
        when(pipelineTaskDbService.claim(70L)).thenReturn(1);
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        when(pipelineProductDbService.getById(50L)).thenReturn(preprocessProduct());

        runner.run(70L);

        verify(pipelineTaskDbService).finish(eq(70L), eq(PipelineTaskStatus.FAILED),
                eq(PipelineTaskErrorCode.CHUNK_FAILED.name()),
                eq("任务未带切片策略快照或快照已损坏，无法切片"));
        // 守卫在读取上游产物本体之前：坏快照不读对象、不进管线
        verify(fileStorage, never()).getObject(any(ObjectRef.class));
        verify(chunker, never()).chunk(any());
        assertTrue(mockingDetails(chunker).getInvocations().stream()
                .noneMatch(inv -> "chunk".equals(inv.getMethod().getName())));
    }

    /**
     * 守护 {@code missingStrategySnapshotShouldFailChunkFailed}：该场景行为改动即失败。
     */
    @Test
    void missingStrategySnapshotShouldFailChunkFailed() {
        // 空快照与坏快照同一口径：解析即抛异常，不再回退代码里的默认策略
        WbPipelineTask task = queuedTask();
        task.setStrategySnapshot(null);
        when(pipelineTaskDbService.getById(70L)).thenReturn(task);
        when(pipelineTaskDbService.claim(70L)).thenReturn(1);
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        when(pipelineProductDbService.getById(50L)).thenReturn(preprocessProduct());

        runner.run(70L);

        verify(pipelineTaskDbService).finish(eq(70L), eq(PipelineTaskStatus.FAILED),
                eq(PipelineTaskErrorCode.CHUNK_FAILED.name()),
                eq("任务未带切片策略快照或快照已损坏，无法切片"));
        verify(chunker, never()).chunk(any());
        assertTrue(mockingDetails(chunker).getInvocations().stream()
                .noneMatch(inv -> "chunk".equals(inv.getMethod().getName())));
    }

    /**
     * 守护 {@code failedOutcomeShouldFinishFailedWithoutProduct}：该场景行为改动即失败。
     */
    @Test
    void failedOutcomeShouldFinishFailedWithoutProduct() {
        when(pipelineTaskDbService.getById(70L)).thenReturn(queuedTask());
        when(pipelineTaskDbService.claim(70L)).thenReturn(1);
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        when(pipelineProductDbService.getById(50L)).thenReturn(preprocessProduct());
        when(fileStorage.getObject(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(), "artifacts", "abc".repeat(22))))
                .thenReturn(json(view()));
        ChunkOutcome outcome = new ChunkOutcome();
        outcome.setSuggestedStatus(PipelineTaskStatus.FAILED);
        outcome.setErrorCode(PipelineTaskErrorCode.CHUNK_EMPTY.name());
        outcome.setErrorMsg("无可切内容（元素均被剔除或无文本）");
        when(chunker.chunk(any(ChunkContext.class))).thenReturn(outcome);

        runner.run(70L);

        verify(pipelineTaskDbService).finish(eq(70L), eq(PipelineTaskStatus.FAILED),
                eq(PipelineTaskErrorCode.CHUNK_EMPTY.name()), any());
        verify(fileStorage, never()).putObject(any(byte[].class));
        // IService 的 save/saveBatch 为默认方法，never+匹配器会触发 InvalidUseOfMatchers，用无交互断言
        verifyNoInteractions(chunkSetDbService, chunkDbService);
    }
}
