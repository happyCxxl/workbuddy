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
import com.workbuddy.worker.embedding.strategy.EmbedStrategy;
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
 * 向量化触发用例：策略解析两档（显式指定 / 知识库绑定）/ 上游切片产物校验 /
 * 窗口前置校验（触发时明确报错）/ 执行键查重 / 建任务快照入队。
 *
 * <p>详情组装用例在 {@link EmbedControlDetailTest}；装配与夹具数据见 {@link EmbedControlTestFixture}。
 */
@ExtendWith(MockitoExtension.class)
class EmbedControlTriggerTest {

    private EmbedControlTestFixture fixture;

    private EmbedControlServiceImpl service;

    @BeforeEach
    void setUp() {
        fixture = new EmbedControlTestFixture();
        service = fixture.service();
    }

    /**
     * 绑定策略档：任务快照带策略、入队并回填任务号。
     *
     * <p>守护 {@code embed}：快照缺策略或漏入队即失败。
     */
    @Test
    void boundStrategyShouldDriveTaskSnapshotAndEnqueue() {
        fixture.stubCommon();
        fixture.existingEmbedTask(50L, 66L, null);
        fixture.stubSaveAssignsId(41L);

        var response = service.embed(10L, null, 50L);

        assertEquals(41L, response.getPipelineTaskId());
        assertEquals("embed-bound-v3", response.getStrategyVersion());
        ArgumentCaptor<WbPipelineTask> captor = ArgumentCaptor.forClass(WbPipelineTask.class);
        verify(fixture.pipelineTaskDbService).save(captor.capture());
        WbPipelineTask task = captor.getValue();
        assertEquals(PipelineStage.EMBED.name(), task.getStage());
        assertEquals(PipelineTaskStatus.QUEUED.name(), task.getStatus());
        assertEquals(50L, task.getUpstreamProductId());
        assertEquals(66L, task.getStrategyVersionId());
        assertNotNull(task.getStrategySnapshot());
        assertTrue(task.getStrategySnapshot().contains("text-embedding-v4"));
        verify(fixture.taskQueue).enqueue(41L);
    }

    /**
     * 未绑定策略按 40438 拒绝。
     *
     * <p>守护 {@code embed}：无绑定仍建任务即失败。
     */
    @Test
    void unboundStrategyShouldReject40438() {
        // 知识库未绑定：不再取"启用中最新"兜底，一律拒绝
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        when(fixture.strategyBindingDbService.getByKbAndType(10L, EmbedStrategy.TYPE)).thenReturn(null);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.embed(10L, null, 50L));

        assertEquals(ErrorCode.TASK_STRATEGY_MISSING, e.getErrorCode());
        verify(fixture.pipelineTaskDbService, never()).save(any());
        verify(fixture.taskQueue, never()).enqueue(any());
    }

    /**
     * 绑定开关关闭（测评模式）按 40438 拒绝。
     *
     * <p>守护 {@code embed}：测评库被隐式绑定即失败。
     */
    @Test
    void bindingSwitchOffShouldReject40438() {
        // 绑定开关关闭：即使库内存在绑定行也不生效
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        when(fixture.knowledgeBaseDbService.getActiveById(10L)).thenReturn(fixture.bindingOffKnowledgeBase());

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.embed(10L, null, 50L));

        assertEquals(ErrorCode.TASK_STRATEGY_MISSING, e.getErrorCode());
        verify(fixture.strategyBindingDbService, never()).getByKbAndType(any(), any());
        verify(fixture.pipelineTaskDbService, never()).save(any());
    }

    /**
     * 未指定上游切片产物按 40436 拒绝。
     *
     * <p>守护 {@code embed}：缺上游仍建任务即失败。
     */
    @Test
    void missingUpstreamProductShouldThrow40436() {
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        fixture.stubBoundStrategy();

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.embed(10L, null, null));
        assertEquals(ErrorCode.TASK_UPSTREAM_MISSING, e.getErrorCode());
        verify(fixture.pipelineTaskDbService, never()).save(any());
        verify(fixture.taskQueue, never()).enqueue(any());
    }

    /**
     * 上游产出任务未成功按 40439 拒绝。
     *
     * <p>守护 {@code embed}：失败上游的产物被消费即失败。
     */
    @Test
    void failedUpstreamTaskShouldThrow40439() {
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        fixture.stubBoundStrategy();
        when(fixture.pipelineProductDbService.getById(50L)).thenReturn(fixture.chunkProduct(null));
        // 产出任务已失败，产物行却已经落盘（孤儿产物）：下游门禁拒绝消费
        when(fixture.pipelineTaskDbService.getById(EmbedControlTestFixture.UPSTREAM_TASK_ID))
                .thenReturn(fixture.upstreamTask(PipelineTaskStatus.FAILED));

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.embed(10L, null, 50L));

        assertEquals(ErrorCode.TASK_UPSTREAM_NOT_READY, e.getErrorCode());
        assertTrue(e.getMessage().contains(PipelineTaskStatus.FAILED.name()), e.getMessage());
        verify(fixture.pipelineTaskDbService, never()).save(any());
        verify(fixture.taskQueue, never()).enqueue(any());
    }

    /**
     * 显式策略版本不存在按 40433 拒绝。
     *
     * <p>守护 {@code embed}：未知版本被采用即失败。
     */
    @Test
    void unknownStrategyVersionShouldThrow40433() {
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        when(fixture.strategyVersionDbService.getById(999L)).thenReturn(null);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.embed(10L, 999L, null));
        assertEquals(ErrorCode.STRATEGY_VERSION_NOT_FOUND, e.getErrorCode());
    }

    /**
     * 未指定版本时取知识库绑定策略。
     *
     * <p>守护 {@code embed}：绑定档未生效即失败。
     */
    @Test
    void boundStrategyShouldBeUsedWhenNoExplicitVersion() {
        fixture.stubCommon();
        fixture.existingEmbedTask(50L, 66L, null);
        fixture.stubSaveAssignsId(43L);

        var response = service.embed(10L, null, 50L);

        assertEquals("embed-bound-v3", response.getStrategyVersion());
    }

    /**
     * 绑定行指向的版本已失效按 40438 拒绝。
     *
     * <p>守护 {@code embed}：失效绑定被采用即失败。
     */
    @Test
    void staleBindingShouldReject40438() {
        // 绑定行指向的策略版本行缺失：不再回退"启用中最新"，一律拒绝
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        when(fixture.knowledgeBaseDbService.getActiveById(10L)).thenReturn(fixture.knowledgeBase());
        when(fixture.strategyBindingDbService.getByKbAndType(10L, EmbedStrategy.TYPE)).thenReturn(fixture.binding());
        when(fixture.strategyVersionDbService.getById(66L)).thenReturn(null);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.embed(10L, null, 50L));

        assertEquals(ErrorCode.TASK_STRATEGY_MISSING, e.getErrorCode());
        verify(fixture.pipelineTaskDbService, never()).save(any());
    }

    /**
     * 显式策略版本优先于绑定。
     *
     * <p>守护 {@code embed}：显式档被绑定覆盖即失败。
     */
    @Test
    void explicitStrategyVersionShouldResolveFromDb() {
        fixture.stubCommon();
        when(fixture.strategyVersionDbService.getById(77L))
                .thenReturn(fixture.embedVersion(77L, "embed-strict", "v2", RowStatus.ACTIVE.name()));
        fixture.existingEmbedTask(50L, 77L, null);
        fixture.stubSaveAssignsId(45L);

        var response = service.embed(10L, 77L, 50L);

        assertEquals("embed-strict-v2", response.getStrategyVersion());
        verify(fixture.strategyBindingDbService, never()).getByKbAndType(any(), any());
    }

    /**
     * 窗口预检：切片最大片长超模型窗口按 40435 拒绝。
     *
     * <p>守护 {@code embed}：超窗策略被执行即失败。
     */
    @Test
    void windowIncompatibleShouldThrow40435() {
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        fixture.stubBoundStrategy();
        // 切片策略 softMaxLen=20000 超 text-embedding-v4 窗口（8192×1.5=12288）
        when(fixture.pipelineProductDbService.getById(50L))
                .thenReturn(fixture.chunkProduct("{\"routes\":{\"body\":{\"algorithm\":\"paragraph-aggregate\","
                        + "\"params\":{\"softMaxLen\":20000}}}}"));

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.embed(10L, null, 50L));
        assertEquals(ErrorCode.EMBED_MODEL_INCOMPATIBLE, e.getErrorCode());
        verify(fixture.pipelineTaskDbService, never()).save(any());
        verify(fixture.taskQueue, never()).enqueue(any());
    }

    /**
     * 窗口预检：切片策略快照损坏时跳过校验并照常入队。
     *
     * <p>守护 {@code embed}：损坏快照阻断触发即失败。
     */
    @Test
    void corruptChunkCapabilitySnapshotShouldSkipWindowCheck() {
        // 上游切片产物行的策略快照坏：窗口兼容校验跳过（留空），触发照常入队，不造默认策略
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        fixture.stubBoundStrategy();
        when(fixture.pipelineProductDbService.getById(50L)).thenReturn(fixture.chunkProduct("{oops"));
        fixture.existingEmbedTask(50L, 66L, null);
        fixture.stubSaveAssignsId(46L);

        var response = service.embed(10L, null, 50L);

        assertEquals(46L, response.getPipelineTaskId());
        assertNotNull(response.getStrategyVersion());
        verify(fixture.taskQueue).enqueue(46L);
    }

    /**
     * 窗口预检：无切片策略快照时跳过校验。
     *
     * <p>守护 {@code embed}：无快照被拦即失败。
     */
    @Test
    void windowCompatibleShouldProceed() {
        fixture.stubCommon();
        fixture.existingEmbedTask(50L, 66L, null);
        fixture.stubSaveAssignsId(44L);

        var response = service.embed(10L, null, 50L);

        assertEquals(44L, response.getPipelineTaskId());
    }

    /**
     * 执行键命中 RUNNING 任务按 40431 拒绝。
     *
     * <p>守护 {@code embed}：重复建任务即失败。
     */
    @Test
    void runningShouldReject40431() {
        fixture.stubCommon();
        WbPipelineTask running = new WbPipelineTask();
        running.setId(41L);
        running.setStatus(PipelineTaskStatus.RUNNING.name());
        fixture.existingEmbedTask(50L, 66L, running);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.embed(10L, null, 50L));

        assertEquals(ErrorCode.TASK_ALREADY_PENDING, e.getErrorCode());
        // 拒绝文案带任务号与状态：用户能看出是哪条任务占着执行键
        assertTrue(e.getMessage().contains("taskId=41"), e.getMessage());
        assertTrue(e.getMessage().contains(PipelineTaskStatus.RUNNING.name()), e.getMessage());
        verify(fixture.pipelineTaskDbService, never()).save(any());
    }

    /**
     * 执行键命中 QUEUED 任务按 40431 拒绝。
     *
     * <p>守护 {@code embed}：重复建任务即失败。
     */
    @Test
    void existingQueuedTaskShouldReject40431() {
        fixture.stubCommon();
        WbPipelineTask queued = new WbPipelineTask();
        queued.setId(41L);
        queued.setStatus(PipelineTaskStatus.QUEUED.name());
        fixture.existingEmbedTask(50L, 66L, queued);

        // 执行键命中即拒绝，触发入口不再补投唤醒；QUEUED 补投归启动补偿与低频扫描
        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.embed(10L, null, 50L));

        assertEquals(ErrorCode.TASK_ALREADY_PENDING, e.getErrorCode());
        verify(fixture.pipelineTaskDbService, never()).save(any());
        verify(fixture.taskQueue, never()).enqueue(any());
    }

    /**
     * 窗口恰好等于上限：放行（边界取等不算超）。
     *
     * <p>守护 {@code precheckWindow} 的 {@code bound > allowed} 判定：写成大于等于会误拒临界策略。
     */
    @Test
    void windowExactlyAtLimitShouldProceed() {
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        WbPipelineStrategyVersion row = fixture.embedVersion(66L, "embed-bound", "v3", RowStatus.ACTIVE.name());
        row.setConfigSnapshot("{\"model\":\"text-embedding-v4\",\"contextWindowTokens\":8192}");
        when(fixture.strategyBindingDbService.getByKbAndType(10L, EmbedStrategy.TYPE)).thenReturn(fixture.binding());
        when(fixture.strategyVersionDbService.getById(66L)).thenReturn(row);
        // text-embedding-v4 窗口 8192×1.5 = 12288；切片 softMaxLen 正好 12288 → 不超
        when(fixture.pipelineProductDbService.getById(50L))
                .thenReturn(fixture.chunkProduct("{\"routes\":{\"body\":{\"algorithm\":\"paragraph-aggregate\","
                        + "\"params\":{\"softMaxLen\":12288}}}}"));
        fixture.existingEmbedTask(50L, 66L, null);
        fixture.stubSaveAssignsId(44L);

        var response = service.embed(10L, null, 50L);

        assertEquals(44L, response.getPipelineTaskId());
    }

    /**
     * 上游切片产物校验：未指定 / 不存在 / 环节不符 / 归属不符都拒绝。
     *
     * <p>守护 {@code requireChunkProduct} 的四道校验：任一道放行都会把错产物当上游。
     */
    @Test
    void upstreamProductShouldBeValidated() {
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        fixture.stubBoundStrategy();
        KnowledgeException missing = assertThrows(KnowledgeException.class,
                () -> service.embed(10L, null, null));
        assertEquals(ErrorCode.TASK_UPSTREAM_MISSING, missing.getErrorCode());

        KnowledgeException zero = assertThrows(KnowledgeException.class,
                () -> service.embed(10L, null, 0L));
        assertEquals(ErrorCode.TASK_UPSTREAM_MISSING, zero.getErrorCode());

        when(fixture.pipelineProductDbService.getById(50L)).thenReturn(null);
        KnowledgeException notFound = assertThrows(KnowledgeException.class,
                () -> service.embed(10L, null, 50L));
        assertEquals(ErrorCode.FILE_RESULT_NOT_FOUND, notFound.getErrorCode());

        WbPipelineProduct wrongStage = fixture.chunkProduct(null);
        wrongStage.setStage(PipelineStage.PARSE.name());
        when(fixture.pipelineProductDbService.getById(50L)).thenReturn(wrongStage);
        KnowledgeException stageMismatch = assertThrows(KnowledgeException.class,
                () -> service.embed(10L, null, 50L));
        assertEquals(ErrorCode.FILE_RESULT_NOT_FOUND, stageMismatch.getErrorCode());

        WbPipelineProduct foreign = fixture.chunkProduct(null);
        foreign.setFileResultId(99L);
        when(fixture.pipelineProductDbService.getById(50L)).thenReturn(foreign);
        KnowledgeException foreignError = assertThrows(KnowledgeException.class,
                () -> service.embed(10L, null, 50L));
        assertEquals(ErrorCode.FILE_RESULT_NOT_FOUND, foreignError.getErrorCode());
        verify(fixture.pipelineTaskDbService, never()).save(any());
    }

    /**
     * 越权文件结果按 40401 拒绝。
     *
     * <p>守护 {@code embedDetail}：越权可读即失败。
     */
    @Test
    void otherUserFileResultShouldReject40401() {
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        // 归属看的是知识库归属：换一个登录用户，越权与"不存在"同样返回 40401
        SecurityTestSupport.loginOtherUser();

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.embed(10L, null, 50L));
        assertEquals(ErrorCode.KB_NOT_FOUND, e.getErrorCode());
    }
}
