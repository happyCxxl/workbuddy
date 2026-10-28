package com.workbuddy.biz.service.impl;

import com.workbuddy.biz.testkit.SecurityTestSupport;
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.domain.entity.WbPipelineStrategyVersion;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.enums.task.RowStatus;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.WorkbuddyException;
import com.workbuddy.worker.preprocessing.strategy.PreprocessStrategy;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 预处理触发用例：策略解析两档（显式指定 / 知识库绑定）/ 上游产物校验 / 执行键查重 / 建任务快照入队。
 *
 * <p>详情组装用例在 {@link PreprocessControlDetailTest}；装配与夹具数据见 {@link PreprocessControlTestFixture}。
 */
@ExtendWith(MockitoExtension.class)
class PreprocessControlTriggerTest {

    private PreprocessControlTestFixture fixture;

    private PreprocessControlServiceImpl service;

    @BeforeEach
    void setUp() {
        fixture = new PreprocessControlTestFixture();
        service = fixture.service();
    }

    /**
     * 绑定策略档：任务快照、入队与响应字段。
     *
     * <p>守护 {@code preprocess}：快照缺策略或漏入队即失败。
     */
    @Test
    void boundStrategyShouldDriveTaskSnapshotAndEnqueue() {
        fixture.stubCommon();
        fixture.existingPreprocessTask(50L, 66L, null);
        fixture.stubSaveAssignsId(31L);

        var response = service.preprocess(10L, null, 50L);

        assertEquals(10L, response.getFileResultId());
        assertEquals(31L, response.getPipelineTaskId());
        assertEquals("preproc-bound-v3", response.getStrategyVersion());
        ArgumentCaptor<WbPipelineTask> captor = ArgumentCaptor.forClass(WbPipelineTask.class);
        verify(fixture.pipelineTaskDbService).save(captor.capture());
        WbPipelineTask task = captor.getValue();
        assertEquals(PipelineStage.PREPROCESS.name(), task.getStage());
        assertEquals(PipelineTaskStatus.QUEUED.name(), task.getStatus());
        assertEquals(50L, task.getUpstreamProductId());
        assertEquals(66L, task.getStrategyVersionId());
        assertNotNull(task.getStrategySnapshot());
        assertTrue(task.getStrategySnapshot().contains("headerFooter"));
        verify(fixture.taskQueue).enqueue(31L);
    }

    /**
     * 未绑定策略按 40438 拒绝。
     *
     * <p>守护 {@code preprocess}：无绑定仍建任务即失败。
     */
    @Test
    void unboundStrategyShouldReject40438() {
        // 知识库未绑定：不再取"启用中最新"兜底，一律拒绝
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        when(fixture.strategyBindingDbService.getByKbAndType(10L, PreprocessStrategy.TYPE)).thenReturn(null);

        WorkbuddyException e = assertThrows(WorkbuddyException.class, () -> service.preprocess(10L, null, 50L));

        assertEquals(ErrorCode.TASK_STRATEGY_MISSING, e.getErrorCode());
        verify(fixture.pipelineTaskDbService, never()).save(any());
        verify(fixture.taskQueue, never()).enqueue(any());
    }

    /**
     * 绑定开关关闭时按 40438 拒绝。
     *
     * <p>守护 {@code preprocess}：测评库被隐式绑定即失败。
     */
    @Test
    void bindingSwitchOffShouldReject40438() {
        // 绑定开关关闭：即使库内存在绑定行也不生效
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        when(fixture.knowledgeBaseDbService.getActiveById(10L)).thenReturn(fixture.bindingOffKnowledgeBase());

        WorkbuddyException e = assertThrows(WorkbuddyException.class, () -> service.preprocess(10L, null, 50L));

        assertEquals(ErrorCode.TASK_STRATEGY_MISSING, e.getErrorCode());
        verify(fixture.strategyBindingDbService, never()).getByKbAndType(any(), any());
        verify(fixture.pipelineTaskDbService, never()).save(any());
    }

    /**
     * 显式策略版本解析与启用校验。
     *
     * <p>守护 {@code preprocess}：未知/停用版本被采用即失败。
     */
    @Test
    void explicitStrategyVersionShouldResolveAndValidate() {
        fixture.stubCommon();
        WbPipelineStrategyVersion row = fixture.preprocessVersion(77L, "preproc-strict", "v2",
                "{\"rules\":{\"toc\":{\"action\":\"EXCLUDE\"}}}");
        when(fixture.strategyVersionDbService.getById(77L)).thenReturn(row);
        fixture.existingPreprocessTask(50L, 77L, null);
        fixture.stubSaveAssignsId(33L);

        var response = service.preprocess(10L, 77L, 50L);

        assertEquals("preproc-strict-v2", response.getStrategyVersion());
        verify(fixture.strategyVersionDbService).getById(eq(77L));
    }

    /**
     * 未指定版本时取知识库绑定策略。
     *
     * <p>守护 {@code preprocess}：绑定档未生效即失败。
     */
    @Test
    void boundStrategyShouldBeUsedWhenNoExplicitVersion() {
        fixture.stubCommon();
        fixture.existingPreprocessTask(50L, 66L, null);
        fixture.stubSaveAssignsId(34L);

        var response = service.preprocess(10L, null, 50L);

        assertEquals("preproc-bound-v3", response.getStrategyVersion());
    }

    /**
     * 显式版本覆盖绑定档。
     *
     * <p>守护 {@code preprocess}：绑定覆盖显式即失败。
     */
    @Test
    void explicitStrategyShouldOverrideBinding() {
        fixture.stubCommon();
        WbPipelineStrategyVersion row = fixture.preprocessVersion(77L, "preproc-explicit", "v4",
                "{\"rules\":{\"headerFooter\":{\"action\":\"EXCLUDE\"}}}");
        when(fixture.strategyVersionDbService.getById(77L)).thenReturn(row);
        fixture.existingPreprocessTask(50L, 77L, null);
        fixture.stubSaveAssignsId(35L);

        var response = service.preprocess(10L, 77L, 50L);

        assertEquals("preproc-explicit-v4", response.getStrategyVersion());
        // 显式指定时不走绑定档
        verify(fixture.strategyBindingDbService, never()).getByKbAndType(any(), any());
    }

    /**
     * 绑定行指向的版本已失效按 40438 拒绝。
     *
     * <p>守护 {@code preprocess}：失效绑定被采用即失败。
     */
    @Test
    void staleBindingShouldReject40438() {
        // 绑定行指向的策略版本行缺失：不再回退"启用中最新"，一律拒绝
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        fixture.stubBoundVersion(null);

        WorkbuddyException e = assertThrows(WorkbuddyException.class, () -> service.preprocess(10L, null, 50L));

        assertEquals(ErrorCode.TASK_STRATEGY_MISSING, e.getErrorCode());
        verify(fixture.pipelineTaskDbService, never()).save(any());
    }

    /**
     * 绑定的策略版本已停用按 40438 拒绝。
     *
     * <p>守护 {@code preprocess}：停用版本被采用即失败。
     */
    @Test
    void inactiveBoundVersionShouldReject40438() {
        // 绑定行指向的策略版本行已停用：与缺失同样拒绝
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        WbPipelineStrategyVersion disabled = fixture.boundVersion();
        disabled.setStatus(RowStatus.INACTIVE.name());
        fixture.stubBoundVersion(disabled);

        WorkbuddyException e = assertThrows(WorkbuddyException.class, () -> service.preprocess(10L, null, 50L));

        assertEquals(ErrorCode.TASK_STRATEGY_MISSING, e.getErrorCode());
        verify(fixture.pipelineTaskDbService, never()).save(any());
    }

    /**
     * 显式版本不存在按 40433 拒绝。
     *
     * <p>守护 {@code preprocess}：未知版本被采用即失败。
     */
    @Test
    void unknownStrategyVersionShouldThrow40433() {
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        when(fixture.strategyVersionDbService.getById(999L)).thenReturn(null);

        WorkbuddyException e = assertThrows(WorkbuddyException.class, () -> service.preprocess(10L, 999L, null));
        assertEquals(ErrorCode.STRATEGY_VERSION_NOT_FOUND, e.getErrorCode());
        verify(fixture.pipelineTaskDbService, never()).save(any());
        verify(fixture.pipelineProductDbService, never()).getByFileResultIdAndStage(any(), any());
    }

    /**
     * 未指定上游统一结构产物按 40436 拒绝。
     *
     * <p>守护 {@code preprocess}：缺上游仍建任务即失败。
     */
    @Test
    void missingUpstreamProductShouldThrow40436() {
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        fixture.stubBoundStrategy();

        WorkbuddyException e = assertThrows(WorkbuddyException.class, () -> service.preprocess(10L, null, null));
        assertEquals(ErrorCode.TASK_UPSTREAM_MISSING, e.getErrorCode());
        assertTrue(e.getMessage().contains("未指定上游统一结构产物"), e.getMessage());
        verify(fixture.pipelineTaskDbService, never()).save(any());
        verify(fixture.taskQueue, never()).enqueue(any());
    }

    /**
     * 上游产出任务未成功按 40439 拒绝。
     *
     * <p>守护 {@code preprocess}：失败上游的产物被消费即失败。
     */
    @Test
    void failedUpstreamTaskShouldThrow40439() {
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        fixture.stubBoundStrategy();
        when(fixture.pipelineProductDbService.getById(50L)).thenReturn(fixture.structureProduct());
        // 产出任务已失败，产物行却已经落盘（孤儿产物）：下游门禁拒绝消费
        when(fixture.pipelineTaskDbService.getById(PreprocessControlTestFixture.UPSTREAM_TASK_ID))
                .thenReturn(fixture.upstreamTask(PipelineTaskStatus.FAILED));

        WorkbuddyException e = assertThrows(WorkbuddyException.class, () -> service.preprocess(10L, null, 50L));

        assertEquals(ErrorCode.TASK_UPSTREAM_NOT_READY, e.getErrorCode());
        assertTrue(e.getMessage().contains(PipelineTaskStatus.FAILED.name()), e.getMessage());
        verify(fixture.pipelineTaskDbService, never()).save(any());
        verify(fixture.taskQueue, never()).enqueue(any());
    }

    /**
     * 执行键命中 RUNNING 任务按 40431 拒绝。
     *
     * <p>守护 {@code preprocess}：重复建任务即失败。
     */
    @Test
    void runningShouldReject40431() {
        fixture.stubCommon();
        WbPipelineTask running = new WbPipelineTask();
        running.setId(31L);
        running.setStatus(PipelineTaskStatus.RUNNING.name());
        fixture.existingPreprocessTask(50L, 66L, running);

        WorkbuddyException e = assertThrows(WorkbuddyException.class, () -> service.preprocess(10L, null, 50L));

        assertEquals(ErrorCode.TASK_ALREADY_PENDING, e.getErrorCode());
        // 拒绝文案带任务号与状态：用户能看出是哪条任务占着执行键
        assertTrue(e.getMessage().contains("taskId=31"), e.getMessage());
        assertTrue(e.getMessage().contains(PipelineTaskStatus.RUNNING.name()), e.getMessage());
        verify(fixture.pipelineTaskDbService, never()).save(any());
        verify(fixture.taskQueue, never()).enqueue(any());
    }

    /**
     * 执行键命中 QUEUED 任务按 40431 拒绝。
     *
     * <p>守护 {@code preprocess}：重复建任务即失败。
     */
    @Test
    void existingQueuedTaskShouldReject40431() {
        fixture.stubCommon();
        WbPipelineTask queued = new WbPipelineTask();
        queued.setId(31L);
        queued.setStatus(PipelineTaskStatus.QUEUED.name());
        fixture.existingPreprocessTask(50L, 66L, queued);

        // 执行键命中即拒绝，触发入口不再补投唤醒；QUEUED 补投归启动补偿与低频扫描
        WorkbuddyException e = assertThrows(WorkbuddyException.class, () -> service.preprocess(10L, null, 50L));

        assertEquals(ErrorCode.TASK_ALREADY_PENDING, e.getErrorCode());
        verify(fixture.pipelineTaskDbService, never()).save(any());
        verify(fixture.taskQueue, never()).enqueue(any());
    }

    /**
     * 执行键命中 SUCCESS 任务按 40431 拒绝。
     *
     * <p>守护 {@code preprocess}：已完成任务可重投即失败。
     */
    @Test
    void succeededTaskShouldReject40431() {
        fixture.stubCommon();
        WbPipelineTask succeeded = new WbPipelineTask();
        succeeded.setId(31L);
        succeeded.setStatus(PipelineTaskStatus.SUCCESS.name());
        fixture.existingPreprocessTask(50L, 66L, succeeded);

        // 查重只看执行键、不看任务状态
        WorkbuddyException e = assertThrows(WorkbuddyException.class, () -> service.preprocess(10L, null, 50L));

        assertEquals(ErrorCode.TASK_ALREADY_PENDING, e.getErrorCode());
        verify(fixture.pipelineTaskDbService, never()).save(any());
        verify(fixture.taskQueue, never()).enqueue(any());
    }

    /**
     * 显式上游产物写进任务快照。
     *
     * <p>守护 {@code preprocess}：上游产物丢失即失败。
     */
    @Test
    void specifiedUpstreamProductShouldBeStoredInTask() {
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        fixture.stubBoundStrategy();
        WbPipelineProduct specified = fixture.structureProduct(88L);
        when(fixture.pipelineProductDbService.getById(88L)).thenReturn(specified);
        fixture.existingPreprocessTask(88L, 66L, null);
        fixture.stubSaveAssignsId(34L);

        var response = service.preprocess(10L, null, 88L);

        ArgumentCaptor<WbPipelineTask> captor = ArgumentCaptor.forClass(WbPipelineTask.class);
        verify(fixture.pipelineTaskDbService).save(captor.capture());
        assertEquals(88L, captor.getValue().getUpstreamProductId());
        assertEquals(34L, response.getPipelineTaskId());
    }

    /**
     * 越权文件结果按 40401 拒绝。
     *
     * <p>守护 {@code preprocessDetail}：越权可读即失败。
     */
    @Test
    void otherUserFileResultShouldReject40401() {
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        // 归属看的是知识库归属：换一个登录用户，越权与"不存在"同样返回 40401
        SecurityTestSupport.loginOtherUser();

        WorkbuddyException e = assertThrows(WorkbuddyException.class, () -> service.preprocess(10L, null, 50L));
        assertEquals(ErrorCode.KB_NOT_FOUND, e.getErrorCode());
    }

    /**
     * 上游产物三连校验：不存在、环节不符、归属不符都按 40404 拒绝且不建任务。
     *
     * <p>守护 {@code requireStructureProduct} 的三道门禁：任一漏判都会消费错产物。
     */
    @Test
    void upstreamProductChecksShouldRejectMissingWrongStageAndForeign() {
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        fixture.stubBoundStrategy();

        when(fixture.pipelineProductDbService.getById(999L)).thenReturn(null);
        WorkbuddyException missing = assertThrows(WorkbuddyException.class,
                () -> service.preprocess(10L, null, 999L));
        assertEquals(ErrorCode.FILE_RESULT_NOT_FOUND, missing.getErrorCode());
        assertTrue(missing.getMessage().contains("不存在"), missing.getMessage());

        WbPipelineProduct wrongStage = fixture.structureProduct();
        wrongStage.setStage(PipelineStage.CHUNK.name());
        when(fixture.pipelineProductDbService.getById(50L)).thenReturn(wrongStage);
        WorkbuddyException stageMismatch = assertThrows(WorkbuddyException.class,
                () -> service.preprocess(10L, null, 50L));
        assertEquals(ErrorCode.FILE_RESULT_NOT_FOUND, stageMismatch.getErrorCode());
        assertTrue(stageMismatch.getMessage().contains("环节不匹配"), stageMismatch.getMessage());

        WbPipelineProduct foreign = fixture.structureProduct();
        foreign.setFileResultId(99L);
        when(fixture.pipelineProductDbService.getById(50L)).thenReturn(foreign);
        WorkbuddyException foreignError = assertThrows(WorkbuddyException.class,
                () -> service.preprocess(10L, null, 50L));
        assertEquals(ErrorCode.FILE_RESULT_NOT_FOUND, foreignError.getErrorCode());
        assertTrue(foreignError.getMessage().contains("不属于该文件结果"), foreignError.getMessage());

        verify(fixture.pipelineTaskDbService, never()).save(any());
        verify(fixture.taskQueue, never()).enqueue(any());
    }
}
