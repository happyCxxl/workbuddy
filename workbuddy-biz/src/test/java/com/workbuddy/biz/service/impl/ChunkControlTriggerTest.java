package com.workbuddy.biz.service.impl;

import com.workbuddy.biz.testkit.SecurityTestSupport;
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.domain.entity.WbPipelineStrategyVersion;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.enums.task.RowStatus;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.worker.chunking.strategy.ChunkStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 切片触发用例：策略解析两档（显式指定 / 知识库绑定）/ 上游产物校验 / 执行键查重 / 建任务快照入队。
 *
 * <p>详情组装用例在 {@link ChunkControlDetailTest}；装配与夹具数据见 {@link ChunkControlTestFixture}。
 */
@ExtendWith(MockitoExtension.class)
class ChunkControlTriggerTest {

    private ChunkControlTestFixture fixture;

    private ChunkControlServiceImpl service;

    @BeforeEach
    void setUp() {
        fixture = new ChunkControlTestFixture();
        service = fixture.service();
    }

    /**
     * 绑定策略档：任务快照带策略、入队并回填任务号。
     *
     * <p>守护 {@code chunk}：快照缺策略或漏入队即失败。
     */
    @Test
    void boundStrategyShouldDriveTaskSnapshotAndEnqueue() {
        fixture.stubCommon();
        fixture.existingChunkTask(50L, 66L, null);
        fixture.stubSaveAssignsId(41L);

        var response = service.chunk(10L, null, 50L);

        assertEquals(41L, response.getPipelineTaskId());
        assertEquals("chunk-bound-v3", response.getStrategyVersion());
        ArgumentCaptor<WbPipelineTask> captor = ArgumentCaptor.forClass(WbPipelineTask.class);
        verify(fixture.pipelineTaskDbService).save(captor.capture());
        WbPipelineTask task = captor.getValue();
        assertEquals(PipelineStage.CHUNK.name(), task.getStage());
        assertEquals(PipelineTaskStatus.QUEUED.name(), task.getStatus());
        assertEquals(50L, task.getUpstreamProductId());
        assertEquals(66L, task.getStrategyVersionId());
        assertNotNull(task.getStrategySnapshot());
        assertTrue(task.getStrategySnapshot().contains("titleInContent"));
        // 策略类型必须随快照下发（解析器按类型分派）
        assertTrue(task.getStrategySnapshot().contains("\"type\":\"CHUNK\""), task.getStrategySnapshot());
        verify(fixture.taskQueue).enqueue(41L);
    }

    /**
     * 未绑定策略按 40438 拒绝。
     *
     * <p>守护 {@code chunk}：无绑定仍建任务即失败。
     */
    @Test
    void unboundStrategyShouldReject40438() {
        // 知识库未绑定：不再取"启用中最新"兜底，一律拒绝
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        when(fixture.strategyBindingDbService.getByKbAndType(10L, ChunkStrategy.TYPE)).thenReturn(null);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.chunk(10L, null, 50L));

        assertEquals(ErrorCode.TASK_STRATEGY_MISSING, e.getErrorCode());
        verify(fixture.pipelineTaskDbService, never()).save(any());
        verify(fixture.taskQueue, never()).enqueue(any());
    }

    /**
     * 绑定开关关闭（测评模式）按 40438 拒绝。
     *
     * <p>守护 {@code chunk}：测评库被隐式绑定即失败。
     */
    @Test
    void bindingSwitchOffShouldReject40438() {
        // 绑定开关关闭：即使库内存在绑定行也不生效
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        when(fixture.knowledgeBaseDbService.getActiveById(10L)).thenReturn(fixture.bindingOffKnowledgeBase());

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.chunk(10L, null, 50L));

        assertEquals(ErrorCode.TASK_STRATEGY_MISSING, e.getErrorCode());
        verify(fixture.strategyBindingDbService, never()).getByKbAndType(any(), any());
        verify(fixture.pipelineTaskDbService, never()).save(any());
    }

    /**
     * 未指定上游预处理产物按 40436 拒绝。
     *
     * <p>守护 {@code chunk}：缺上游仍建任务即失败。
     */
    @Test
    void missingUpstreamProductShouldThrow40436() {
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        fixture.stubBoundStrategy();

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.chunk(10L, null, null));
        assertEquals(ErrorCode.TASK_UPSTREAM_MISSING, e.getErrorCode());
        verify(fixture.pipelineTaskDbService, never()).save(any());
        verify(fixture.taskQueue, never()).enqueue(any());
    }

    /**
     * 上游产出任务未成功按 40439 拒绝。
     *
     * <p>守护 {@code chunk}：失败上游的产物被消费即失败。
     */
    @Test
    void failedUpstreamTaskShouldThrow40439() {
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        fixture.stubBoundStrategy();
        when(fixture.pipelineProductDbService.getById(50L)).thenReturn(fixture.preprocessProduct());
        // 产出任务已失败，产物行却已经落盘（孤儿产物）：下游门禁拒绝消费
        when(fixture.pipelineTaskDbService.getById(ChunkControlTestFixture.UPSTREAM_TASK_ID))
                .thenReturn(fixture.upstreamTask(PipelineTaskStatus.FAILED));

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.chunk(10L, null, 50L));

        assertEquals(ErrorCode.TASK_UPSTREAM_NOT_READY, e.getErrorCode());
        assertTrue(e.getMessage().contains(PipelineTaskStatus.FAILED.name()), e.getMessage());
        verify(fixture.pipelineTaskDbService, never()).save(any());
        verify(fixture.taskQueue, never()).enqueue(any());
    }

    /**
     * 显式策略版本不存在按 40433 拒绝。
     *
     * <p>守护 {@code chunk}：未知版本被采用即失败。
     */
    @Test
    void unknownStrategyVersionShouldThrow40433() {
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        when(fixture.strategyVersionDbService.getById(999L)).thenReturn(null);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.chunk(10L, 999L, 50L));
        assertEquals(ErrorCode.STRATEGY_VERSION_NOT_FOUND, e.getErrorCode());
        verify(fixture.pipelineProductDbService, never()).getByFileResultIdAndStage(any(), any());
    }

    /**
     * 显式策略版本优先于绑定。
     *
     * <p>守护 {@code chunk}：显式档被绑定覆盖即失败。
     */
    @Test
    void explicitStrategyVersionShouldResolveFromDb() {
        fixture.stubCommon();
        when(fixture.strategyVersionDbService.getById(77L))
                .thenReturn(fixture.chunkVersion(77L, "chunk-strict", "v2"));
        fixture.existingChunkTask(50L, 77L, null);
        fixture.stubSaveAssignsId(42L);

        var response = service.chunk(10L, 77L, 50L);

        assertEquals("chunk-strict-v2", response.getStrategyVersion());
        ArgumentCaptor<WbPipelineTask> captor = ArgumentCaptor.forClass(WbPipelineTask.class);
        verify(fixture.pipelineTaskDbService).save(captor.capture());
        assertTrue(captor.getValue().getStrategySnapshot().contains("titleInContent"));
        assertEquals(77L, captor.getValue().getStrategyVersionId());
    }

    /**
     * 未指定版本时取知识库绑定策略。
     *
     * <p>守护 {@code chunk}：绑定档未生效即失败。
     */
    @Test
    void boundStrategyShouldBeUsedWhenNoExplicitVersion() {
        fixture.stubCommon();
        fixture.existingChunkTask(50L, 66L, null);
        fixture.stubSaveAssignsId(43L);

        var response = service.chunk(10L, null, 50L);

        assertEquals("chunk-bound-v3", response.getStrategyVersion());
    }

    /**
     * 显式版本覆盖绑定档。
     *
     * <p>守护 {@code chunk}：绑定覆盖显式即失败。
     */
    @Test
    void explicitStrategyShouldOverrideBinding() {
        fixture.stubCommon();
        when(fixture.strategyVersionDbService.getById(77L))
                .thenReturn(fixture.chunkVersion(77L, "chunk-explicit", "v4"));
        fixture.existingChunkTask(50L, 77L, null);
        fixture.stubSaveAssignsId(44L);

        var response = service.chunk(10L, 77L, 50L);

        assertEquals("chunk-explicit-v4", response.getStrategyVersion());
        // 显式指定时不走绑定档
        verify(fixture.strategyBindingDbService, never()).getByKbAndType(any(), any());
    }

    /**
     * 绑定行指向的版本已失效按 40438 拒绝。
     *
     * <p>守护 {@code chunk}：失效绑定被采用即失败。
     */
    @Test
    void staleBindingShouldReject40438() {
        // 绑定行指向的策略版本行缺失：不再回退"启用中最新"，一律拒绝
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        when(fixture.knowledgeBaseDbService.getActiveById(10L)).thenReturn(fixture.knowledgeBase());
        when(fixture.strategyBindingDbService.getByKbAndType(10L, ChunkStrategy.TYPE)).thenReturn(fixture.binding());
        when(fixture.strategyVersionDbService.getById(66L)).thenReturn(null);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.chunk(10L, null, 50L));

        assertEquals(ErrorCode.TASK_STRATEGY_MISSING, e.getErrorCode());
        verify(fixture.pipelineTaskDbService, never()).save(any());
    }

    /**
     * 策略类型不是 CHUNK 按 40433 拒绝。
     *
     * <p>守护 {@code chunk}：错类型策略被执行即失败。
     */
    @Test
    void mismatchedStrategyTypeShouldReject() {
        // 显式策略按行 id 引用，类型不匹配拒绝
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        WbPipelineStrategyVersion wrongType = fixture.chunkVersion(77L, "chunk-x", "v1");
        wrongType.setType("PREPROCESS");
        when(fixture.strategyVersionDbService.getById(77L)).thenReturn(wrongType);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.chunk(10L, 77L, 50L));
        assertEquals(ErrorCode.STRATEGY_VERSION_NOT_FOUND, e.getErrorCode());
    }

    /**
     * 执行键命中 RUNNING 任务按 40431 拒绝。
     *
     * <p>守护 {@code chunk}：重复建任务即失败。
     */
    @Test
    void runningShouldReject40431() {
        fixture.stubCommon();
        WbPipelineTask running = new WbPipelineTask();
        running.setId(41L);
        running.setStatus(PipelineTaskStatus.RUNNING.name());
        fixture.existingChunkTask(50L, 66L, running);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.chunk(10L, null, 50L));

        assertEquals(ErrorCode.TASK_ALREADY_PENDING, e.getErrorCode());
        // 拒绝文案带任务号与状态：用户能看出是哪条任务占着执行键
        assertTrue(e.getMessage().contains("taskId=41"), e.getMessage());
        assertTrue(e.getMessage().contains(PipelineTaskStatus.RUNNING.name()), e.getMessage());
        verify(fixture.pipelineTaskDbService, never()).save(any());
        verify(fixture.taskQueue, never()).enqueue(any());
    }

    /**
     * 执行键命中 QUEUED 任务按 40431 拒绝。
     *
     * <p>守护 {@code chunk}：重复建任务即失败。
     */
    @Test
    void existingQueuedTaskShouldReject40431() {
        fixture.stubCommon();
        WbPipelineTask queued = new WbPipelineTask();
        queued.setId(41L);
        queued.setStatus(PipelineTaskStatus.QUEUED.name());
        fixture.existingChunkTask(50L, 66L, queued);

        // 执行键命中即拒绝，触发入口不再补投唤醒；QUEUED 补投归启动补偿与低频扫描
        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.chunk(10L, null, 50L));

        assertEquals(ErrorCode.TASK_ALREADY_PENDING, e.getErrorCode());
        verify(fixture.pipelineTaskDbService, never()).save(any());
        verify(fixture.taskQueue, never()).enqueue(any());
    }

    /**
     * 越权文件结果按 40401 拒绝。
     *
     * <p>守护 {@code chunkDetail}：越权可读即失败。
     */
    @Test
    void otherUserFileResultShouldReject40401() {
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        // 归属看的是知识库归属：换一个登录用户，越权与"不存在"同样返回 40401
        SecurityTestSupport.loginOtherUser();

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.chunk(10L, null, 50L));
        assertEquals(ErrorCode.KB_NOT_FOUND, e.getErrorCode());
    }

    /**
     * 上游预处理产物三连校验：不存在、环节不符、归属不符都按 40404 拒绝且不建任务。
     *
     * <p>守护 {@code requirePreprocessProduct} 的三道门禁。
     */
    @Test
    void upstreamProductChecksShouldRejectMissingWrongStageAndForeign() {
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        fixture.stubBoundStrategy();

        when(fixture.pipelineProductDbService.getById(999L)).thenReturn(null);
        KnowledgeException missing = assertThrows(KnowledgeException.class,
                () -> service.chunk(10L, null, 999L));
        assertEquals(ErrorCode.FILE_RESULT_NOT_FOUND, missing.getErrorCode());
        assertTrue(missing.getMessage().contains("不存在"), missing.getMessage());

        WbPipelineProduct wrongStage = fixture.preprocessProduct();
        wrongStage.setStage(PipelineStage.PARSE.name());
        when(fixture.pipelineProductDbService.getById(50L)).thenReturn(wrongStage);
        KnowledgeException stageMismatch = assertThrows(KnowledgeException.class,
                () -> service.chunk(10L, null, 50L));
        assertEquals(ErrorCode.FILE_RESULT_NOT_FOUND, stageMismatch.getErrorCode());
        assertTrue(stageMismatch.getMessage().contains("环节不匹配"), stageMismatch.getMessage());

        WbPipelineProduct foreign = fixture.preprocessProduct();
        foreign.setFileResultId(99L);
        when(fixture.pipelineProductDbService.getById(50L)).thenReturn(foreign);
        KnowledgeException foreignError = assertThrows(KnowledgeException.class,
                () -> service.chunk(10L, null, 50L));
        assertEquals(ErrorCode.FILE_RESULT_NOT_FOUND, foreignError.getErrorCode());
        assertTrue(foreignError.getMessage().contains("不属于该文件结果"), foreignError.getMessage());

        verify(fixture.pipelineTaskDbService, never()).save(any());
        verify(fixture.taskQueue, never()).enqueue(any());
    }

    /**
     * 显式策略版本类型与环节不符：按 40433 拒绝。
     *
     * <p>守护 {@code requireExplicit} 的类型校验：漏校验会把向量化策略当切片策略用。
     */
    @Test
    void explicitStrategyWithWrongTypeShouldReject() {
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        WbPipelineStrategyVersion wrongType = fixture.chunkVersion(77L, "embed-default", "v1");
        wrongType.setType("EMBED");
        when(fixture.strategyVersionDbService.getById(77L)).thenReturn(wrongType);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.chunk(10L, 77L, null));

        assertEquals(ErrorCode.STRATEGY_VERSION_NOT_FOUND, e.getErrorCode());
        assertTrue(e.getMessage().contains("类型不匹配"), e.getMessage());
    }

    /**
     * 显式策略版本已停用：按 40433 拒绝并提示启用。
     *
     * <p>守护 {@code requireExplicit} 的启用校验：停用策略被采用即失败。
     */
    @Test
    void explicitStrategyInactiveShouldReject() {
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        WbPipelineStrategyVersion inactive = fixture.chunkVersion(77L, "chunk-strict", "v1");
        inactive.setStatus(RowStatus.INACTIVE.name());
        when(fixture.strategyVersionDbService.getById(77L)).thenReturn(inactive);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.chunk(10L, 77L, null));

        assertEquals(ErrorCode.STRATEGY_VERSION_NOT_FOUND, e.getErrorCode());
        assertTrue(e.getMessage().contains("已停用"), e.getMessage());
    }
}
