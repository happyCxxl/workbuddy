package com.workbuddy.biz.service.impl;

import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.WorkbuddyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 结构组装触发用例：上游产物校验 / 防重 / 建任务入队（upstream 链）。
 *
 * <p>详情组装用例在 {@link StructureDetailTest}；装配与夹具数据见 {@link StructureControlTestFixture}。
 */
@ExtendWith(MockitoExtension.class)
class StructureTriggerTest {

    private StructureControlTestFixture fixture;

    private StructureControlServiceImpl service;

    @BeforeEach
    void setUp() {
        fixture = new StructureControlTestFixture();
        service = fixture.service();
    }

    /**
     * 组装：以上游解析产物建任务并入队。
     *
     * <p>守护 {@code structure}：上游产物或任务字段错即失败。
     */
    @Test
    void structureShouldCreateTaskWithUpstream() {
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        WbPipelineProduct parseProduct = fixture.parseProduct();
        parseProduct.setArtifactId("abc");
        when(fixture.pipelineProductDbService.getById(50L)).thenReturn(parseProduct);
        fixture.existingStructureTask(50L, null);
        fixture.stubSaveAssignsId(51L);

        var response = service.structure(10L, 50L);

        assertEquals(51L, response.getPipelineTaskId());
        ArgumentCaptor<WbPipelineTask> captor = ArgumentCaptor.forClass(WbPipelineTask.class);
        verify(fixture.pipelineTaskDbService).save(captor.capture());
        assertEquals(PipelineStage.STRUCTURE.name(), captor.getValue().getStage());
        assertEquals(50L, captor.getValue().getUpstreamProductId());
        // 组装无策略：任务行的策略位落 0 哨兵
        assertEquals(0L, captor.getValue().getStrategyVersionId());
        verify(fixture.taskQueue).enqueue(51L);
    }

    /**
     * 组装：显式指定上游产物优先。
     *
     * <p>守护 {@code structure}：显式档被覆盖即失败。
     */
    @Test
    void structureShouldUseSpecifiedUpstreamProduct() {
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        // 指定路径：按 id 查产物并校验
        when(fixture.pipelineProductDbService.getById(88L)).thenReturn(fixture.parseProduct(88L));
        fixture.existingStructureTask(88L, null);
        fixture.stubSaveAssignsId(89L);

        var response = service.structure(10L, 88L);

        assertEquals(89L, response.getPipelineTaskId());
        ArgumentCaptor<WbPipelineTask> captor = ArgumentCaptor.forClass(WbPipelineTask.class);
        verify(fixture.pipelineTaskDbService).save(captor.capture());
        assertEquals(88L, captor.getValue().getUpstreamProductId());
    }

    /**
     * 组装：上游产物环节不符按 40404 拒绝。
     *
     * <p>守护 {@code structure}：错环节产物被消费即失败。
     */
    @Test
    void structureWithMismatchedProductShouldReject() {
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        WbPipelineProduct wrongStage = fixture.parseProduct(88L);
        wrongStage.setStage(PipelineStage.CHUNK.name());
        when(fixture.pipelineProductDbService.getById(88L)).thenReturn(wrongStage);

        WorkbuddyException e = assertThrows(WorkbuddyException.class, () -> service.structure(10L, 88L));
        assertEquals(ErrorCode.FILE_RESULT_NOT_FOUND, e.getErrorCode());
        assertTrue(e.getMessage().contains("指定产物环节不匹配"), e.getMessage());
        verify(fixture.pipelineTaskDbService, never()).save(any());
    }

    /**
     * 组装：上游任务未成功按 40439 拒绝。
     *
     * <p>守护 {@code structure}：失败上游的产物被消费即失败。
     */
    @Test
    void structureWithFailedUpstreamTaskShouldThrow40439() {
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        when(fixture.pipelineProductDbService.getById(50L)).thenReturn(fixture.parseProduct());
        // 产出任务已失败，产物行却已经落盘（孤儿产物）：下游门禁拒绝消费
        when(fixture.pipelineTaskDbService.getById(StructureControlTestFixture.UPSTREAM_TASK_ID))
                .thenReturn(fixture.upstreamTask(PipelineTaskStatus.FAILED));

        WorkbuddyException e = assertThrows(WorkbuddyException.class, () -> service.structure(10L, 50L));

        assertEquals(ErrorCode.TASK_UPSTREAM_NOT_READY, e.getErrorCode());
        assertTrue(e.getMessage().contains(PipelineTaskStatus.FAILED.name()), e.getMessage());
        assertTrue(e.getMessage().contains("请重新触发上游环节"), e.getMessage());
        verify(fixture.pipelineTaskDbService, never()).save(any());
        verify(fixture.taskQueue, never()).enqueue(any());
    }

    /**
     * 组装：上游部分成功在放行集内。
     *
     * <p>守护 {@code structure}：部分成功被拦即失败。
     */
    @Test
    void structureWithPartialSuccessUpstreamTaskShouldPass() {
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        when(fixture.pipelineProductDbService.getById(50L)).thenReturn(fixture.parseProduct());
        when(fixture.pipelineTaskDbService.getById(StructureControlTestFixture.UPSTREAM_TASK_ID))
                .thenReturn(fixture.upstreamTask(PipelineTaskStatus.PARTIAL_SUCCESS));
        fixture.existingStructureTask(50L, null);
        fixture.stubSaveAssignsId(52L);

        var response = service.structure(10L, 50L);

        // 部分成功在放行集内：任务照常新建并入队
        assertEquals(52L, response.getPipelineTaskId());
        verify(fixture.taskQueue).enqueue(52L);
    }

    /**
     * 组装：未指定上游解析产物按 40436 拒绝。
     *
     * <p>守护 {@code structure}：缺上游仍建任务即失败。
     */
    @Test
    void structureWithoutParseProductShouldThrow40436() {
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());

        WorkbuddyException e = assertThrows(WorkbuddyException.class, () -> service.structure(10L, null));
        assertEquals(ErrorCode.TASK_UPSTREAM_MISSING, e.getErrorCode());
        assertTrue(e.getMessage().contains("未指定上游解析产物"), e.getMessage());
        verify(fixture.pipelineTaskDbService, never()).save(any());
    }

    /**
     * 组装：执行键命中运行中任务按 40431 拒绝。
     *
     * <p>守护 {@code structure}：重复建任务即失败。
     */
    @Test
    void structureRunningShouldReject() {
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        when(fixture.pipelineProductDbService.getById(50L)).thenReturn(fixture.parseProduct());
        fixture.existingStructureTask(50L, fixture.taskWithStatus(51L, PipelineTaskStatus.RUNNING));

        WorkbuddyException e = assertThrows(WorkbuddyException.class, () -> service.structure(10L, 50L));

        assertEquals(ErrorCode.TASK_ALREADY_PENDING, e.getErrorCode());
        // 拒绝文案带任务号与状态：用户能看出是哪条任务占着执行键
        assertTrue(e.getMessage().contains("taskId=51"), e.getMessage());
        assertTrue(e.getMessage().contains(PipelineTaskStatus.RUNNING.name()), e.getMessage());
        verify(fixture.pipelineTaskDbService, never()).save(any());
        verify(fixture.taskQueue, never()).enqueue(any());
    }

    /**
     * 组装：执行键命中排队任务按 40431 拒绝。
     *
     * <p>守护 {@code structure}：重复建任务即失败。
     */
    @Test
    void structureExistingQueuedTaskShouldReject40431() {
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        when(fixture.pipelineProductDbService.getById(50L)).thenReturn(fixture.parseProduct());
        fixture.existingStructureTask(50L, fixture.taskWithStatus(51L, PipelineTaskStatus.QUEUED));

        // 执行键命中即拒绝，触发入口不再补投唤醒；QUEUED 补投归启动补偿与低频扫描
        WorkbuddyException e = assertThrows(WorkbuddyException.class, () -> service.structure(10L, 50L));

        assertEquals(ErrorCode.TASK_ALREADY_PENDING, e.getErrorCode());
        verify(fixture.pipelineTaskDbService, never()).save(any());
        verify(fixture.taskQueue, never()).enqueue(any());
    }

    /**
     * 详情：本人文件结果通过归属校验。
     *
     * <p>守护 {@code structureDetail}：本人也被拦即失败。
     */
    @Test
    void ownerFileResultShouldPassOwnershipCheck() {
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());

        // 归属校验放行后进入业务分支：没有解析产物时报"产物不存在"，而不是归属的 40401
        WorkbuddyException e = assertThrows(WorkbuddyException.class, () -> service.structure(10L, 50L));
        assertEquals(ErrorCode.FILE_RESULT_NOT_FOUND, e.getErrorCode());
    }

    /**
     * 指定上游产物属于别的文件结果：按 40404 拒绝。
     *
     * <p>守护 {@code requireParseProduct} 的归属校验：跨文件产物被消费会把 A 的结构写进 B。
     */
    @Test
    void structureWithForeignParseProductShouldReject() {
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        WbPipelineProduct foreign = fixture.parseProduct(88L);
        foreign.setFileResultId(99L);
        when(fixture.pipelineProductDbService.getById(88L)).thenReturn(foreign);

        WorkbuddyException e = assertThrows(WorkbuddyException.class, () -> service.structure(10L, 88L));

        assertEquals(ErrorCode.FILE_RESULT_NOT_FOUND, e.getErrorCode());
        assertTrue(e.getMessage().contains("不属于该文件结果"), e.getMessage());
        verify(fixture.pipelineTaskDbService, never()).save(any());
    }
}
