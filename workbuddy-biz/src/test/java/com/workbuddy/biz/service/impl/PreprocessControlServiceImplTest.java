package com.workbuddy.biz.service.impl;

import com.workbuddy.biz.service.db.KbFileResultDbService;
import com.workbuddy.biz.service.db.KbPipelineProductDbService;
import com.workbuddy.biz.service.db.KbPipelineStepLogDbService;
import com.workbuddy.biz.service.db.KbPipelineStrategyVersionDbService;
import com.workbuddy.biz.service.db.KbPipelineTaskDbService;
import com.workbuddy.biz.service.db.KbSourceFileDbService;
import com.workbuddy.biz.service.db.KbStrategyBindingDbService;
import com.workbuddy.biz.service.db.KnowledgeBaseDbService;
import com.workbuddy.biz.service.support.ChainStorageSupport;
import com.workbuddy.biz.service.support.FileResultAccessGuard;
import com.workbuddy.biz.service.support.PreprocessVoAssembler;
import com.workbuddy.biz.service.support.StageStrategySupport;
import com.workbuddy.biz.service.support.TaskDetailSupport;
import com.workbuddy.biz.task.support.TaskQueueSupport;
import com.workbuddy.biz.task.support.TaskTriggerSupport;
import com.workbuddy.biz.task.UpstreamProductGate;
import com.workbuddy.biz.testkit.SecurityTestSupport;
import com.workbuddy.common.domain.entity.KbFileResult;
import com.workbuddy.common.domain.entity.KbPipelineProduct;
import com.workbuddy.common.domain.entity.KbPipelineStepLog;
import com.workbuddy.common.domain.entity.KbPipelineStrategyVersion;
import com.workbuddy.common.domain.entity.KbPipelineTask;
import com.workbuddy.common.domain.entity.KbStrategyBinding;
import com.workbuddy.common.domain.entity.KnowledgeBase;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.dto.response.preprocess.PreprocessDetailVO;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.enums.task.RowStatus;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.filecenter.provider.StorageRouter;
import com.workbuddy.filecenter.service.FileStorage;
import com.workbuddy.worker.preprocessing.PreprocessProperties;
import com.workbuddy.worker.preprocessing.strategy.PreprocessStrategy;
import com.workbuddy.worker.preprocessing.strategy.PreprocessStrategyParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 预处理控制面服务单测：策略解析两档（显式指定 / 知识库绑定）/ 上游产物校验 / 执行键查重 /
 * 建任务快照入队 / 预处理详情。
 *
 * @author cxxl
 */
@ExtendWith(MockitoExtension.class)
class PreprocessControlServiceImplTest {

    /** 产物所在的数据源 */
    private static final Long SOURCE_ID = 1L;

    /** 上游产物的产出任务 ID（门禁按它的状态判定） */
    private static final Long UPSTREAM_TASK_ID = 40L;

    @Mock
    private KbFileResultDbService fileResultDbService;
    @Mock
    private KbPipelineTaskDbService pipelineTaskDbService;
    @Mock
    private KbPipelineProductDbService pipelineProductDbService;
    @Mock
    private KbPipelineStrategyVersionDbService strategyVersionDbService;
    @Mock
    private KbPipelineStepLogDbService stepLogDbService;
    @Mock
    private TaskQueueSupport taskQueue;
    @Mock
    private FileStorage fileStorage;
    @Mock
    private KbSourceFileDbService sourceFileDbService;
    @Mock
    private StorageRouter storageRouter;
    @Mock
    private KbStrategyBindingDbService strategyBindingDbService;
    @Mock
    private KnowledgeBaseDbService knowledgeBaseDbService;

    private PreprocessControlServiceImpl service;

    @BeforeEach
    void setUp() {
        // 触发/详情助手为纯委托类、组装器为纯映射类，用真实实例（mock 会让 VO 组装返回 null，断言失真）
        SecurityTestSupport.loginViewer();
        boundKnowledgeBase(10L);
        FileResultAccessGuard accessGuard = new FileResultAccessGuard(fileResultDbService, knowledgeBaseDbService);
        ChainStorageSupport chainStorage = new ChainStorageSupport(fileResultDbService, sourceFileDbService,
                fileStorage, storageRouter);
        service = new PreprocessControlServiceImpl(pipelineProductDbService,
                new StageStrategySupport(strategyVersionDbService, strategyBindingDbService, knowledgeBaseDbService),
                new TaskTriggerSupport(pipelineTaskDbService, chainStorage, taskQueue),
                new TaskDetailSupport(pipelineTaskDbService, stepLogDbService, pipelineProductDbService), fileStorage,
                new PreprocessStrategyParser(new PreprocessProperties()),
                new PreprocessVoAssembler(), accessGuard, new UpstreamProductGate(pipelineTaskDbService));
        // 上游产物的产出任务默认成功：门禁放行；不放行的状态由各用例自行改写（宽松桩）
        lenient().when(pipelineTaskDbService.getById(UPSTREAM_TASK_ID)).thenReturn(upstreamTask(PipelineTaskStatus.SUCCESS));
    }

    /** 上游产物的产出任务（环节 STRUCTURE、归属文件结果 10） */
    private KbPipelineTask upstreamTask(PipelineTaskStatus status) {
        KbPipelineTask task = new KbPipelineTask();
        task.setId(UPSTREAM_TASK_ID);
        task.setFileResultId(10L);
        task.setStage(PipelineStage.STRUCTURE.name());
        task.setStatus(status.name());
        return task;
    }

    private KbFileResult fileResult() {
        KbFileResult fileResult = new KbFileResult();
        fileResult.setId(10L);
        fileResult.setKnowledgeBaseId(10L);
        return fileResult;
    }

    private KbPipelineProduct structureProduct() {
        KbPipelineProduct product = new KbPipelineProduct();
        product.setId(50L);
        product.setTaskId(UPSTREAM_TASK_ID);
        product.setFileResultId(10L);
        product.setStage(PipelineStage.STRUCTURE.name());
        return product;
    }

    private void stubCommon() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        when(pipelineProductDbService.getById(50L)).thenReturn(structureProduct());
        stubBoundStrategy();
    }

    /** 绑定档桩：KB 10 → 绑定行 → 启用中的策略版本行 66（显式指定档的用例不命中它，按宽松桩登记） */
    private void stubBoundStrategy() {
        lenient().when(strategyBindingDbService.getByKbAndType(10L, PreprocessStrategy.TYPE)).thenReturn(binding());
        lenient().when(strategyVersionDbService.getById(66L)).thenReturn(boundVersion());
    }

    /** 预处理执行键检查：文件结果 10 + 上游组装产物 + 策略版本（0 表示该环节无策略） */
    private void existingPreprocessTask(Long upstreamProductId, Long strategyVersionId, KbPipelineTask task) {
        when(pipelineTaskDbService.getByExecKey(10L, PipelineStage.PREPROCESS.name(), upstreamProductId,
                strategyVersionId)).thenReturn(task);
    }

    /** 知识库夹具（绑定开关未显式关闭 → 视为开启） */
    private KnowledgeBase knowledgeBase() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(10L);
        kb.setStrategyBindingEnabled(1);
        kb.setUserId(SecurityTestSupport.VIEWER_ID);
        return kb;
    }

    /** 绑定开关关闭的知识库 */
    private KnowledgeBase bindingOffKnowledgeBase() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(10L);
        kb.setStrategyBindingEnabled(0);
        kb.setUserId(SecurityTestSupport.VIEWER_ID);
        return kb;
    }

    /** 有效绑定行：KB 10 绑定 PREPROCESS 策略版本 66 */
    private KbStrategyBinding binding() {
        KbStrategyBinding binding = new KbStrategyBinding();
        binding.setId(1L);
        binding.setKnowledgeBaseId(10L);
        binding.setStrategyType(PreprocessStrategy.TYPE);
        binding.setStrategyVersionId(66L);
        return binding;
    }

    /** 绑定行指向的策略版本行（启用中） */
    private KbPipelineStrategyVersion boundVersion() {
        KbPipelineStrategyVersion row = new KbPipelineStrategyVersion();
        row.setId(66L);
        row.setType(PreprocessStrategy.TYPE);
        row.setName("preproc-bound");
        row.setVersion("v3");
        row.setStatus("ACTIVE");
        row.setConfigSnapshot("{\"rules\":{\"headerFooter\":{\"action\":\"KEEP\"}}}");
        return row;
    }

    @Test
    void boundStrategyShouldDriveTaskSnapshotAndEnqueue() {
        stubCommon();
        existingPreprocessTask(50L, 66L, null);
        when(pipelineTaskDbService.save(any(KbPipelineTask.class))).thenAnswer(inv -> {
            inv.getArgument(0, KbPipelineTask.class).setId(31L);
            return true;
        });

        var response = service.preprocess(10L, null, 50L);

        assertEquals(10L, response.getFileResultId());
        assertEquals(31L, response.getPipelineTaskId());
        assertEquals("preproc-bound-v3", response.getStrategyVersion());
        ArgumentCaptor<KbPipelineTask> captor = ArgumentCaptor.forClass(KbPipelineTask.class);
        verify(pipelineTaskDbService).save(captor.capture());
        KbPipelineTask task = captor.getValue();
        assertEquals(PipelineStage.PREPROCESS.name(), task.getStage());
        assertEquals(PipelineTaskStatus.QUEUED.name(), task.getStatus());
        assertEquals(50L, task.getUpstreamProductId());
        assertEquals(66L, task.getStrategyVersionId());
        assertNotNull(task.getStrategySnapshot());
        assertTrue(task.getStrategySnapshot().contains("headerFooter"));
        verify(taskQueue).enqueue(31L);
    }

    @Test
    void unboundStrategyShouldReject40438() {
        // 知识库未绑定：不再取"启用中最新"兜底，一律拒绝
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        when(strategyBindingDbService.getByKbAndType(10L, PreprocessStrategy.TYPE)).thenReturn(null);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.preprocess(10L, null, 50L));

        assertEquals(ErrorCode.TASK_STRATEGY_MISSING, e.getErrorCode());
        verify(pipelineTaskDbService, never()).save(any());
        verify(taskQueue, never()).enqueue(any());
    }

    @Test
    void bindingSwitchOffShouldReject40438() {
        // 绑定开关关闭：即使库内存在绑定行也不生效
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        when(knowledgeBaseDbService.getActiveById(10L)).thenReturn(bindingOffKnowledgeBase());

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.preprocess(10L, null, 50L));

        assertEquals(ErrorCode.TASK_STRATEGY_MISSING, e.getErrorCode());
        verify(strategyBindingDbService, never()).getByKbAndType(any(), any());
        verify(pipelineTaskDbService, never()).save(any());
    }

    @Test
    void explicitStrategyVersionShouldResolveAndValidate() {
        stubCommon();
        KbPipelineStrategyVersion row = new KbPipelineStrategyVersion();
        row.setId(77L);
        row.setType(PreprocessStrategy.TYPE);
        row.setName("preproc-strict");
        row.setVersion("v2");
        row.setStatus("ACTIVE");
        row.setConfigSnapshot("{\"rules\":{\"toc\":{\"action\":\"EXCLUDE\"}}}");
        when(strategyVersionDbService.getById(77L)).thenReturn(row);
        existingPreprocessTask(50L, 77L, null);
        when(pipelineTaskDbService.save(any(KbPipelineTask.class))).thenAnswer(inv -> {
            inv.getArgument(0, KbPipelineTask.class).setId(33L);
            return true;
        });

        var response = service.preprocess(10L, 77L, 50L);

        assertEquals("preproc-strict-v2", response.getStrategyVersion());
        verify(strategyVersionDbService).getById(eq(77L));
    }

    @Test
    void boundStrategyShouldBeUsedWhenNoExplicitVersion() {
        stubCommon();
        existingPreprocessTask(50L, 66L, null);
        when(pipelineTaskDbService.save(any(KbPipelineTask.class))).thenAnswer(inv -> {
            inv.getArgument(0, KbPipelineTask.class).setId(34L);
            return true;
        });

        var response = service.preprocess(10L, null, 50L);

        assertEquals("preproc-bound-v3", response.getStrategyVersion());
    }

    @Test
    void explicitStrategyShouldOverrideBinding() {
        stubCommon();
        KbPipelineStrategyVersion row = new KbPipelineStrategyVersion();
        row.setId(77L);
        row.setType(PreprocessStrategy.TYPE);
        row.setName("preproc-explicit");
        row.setVersion("v4");
        row.setStatus("ACTIVE");
        row.setConfigSnapshot("{\"rules\":{\"headerFooter\":{\"action\":\"EXCLUDE\"}}}");
        when(strategyVersionDbService.getById(77L)).thenReturn(row);
        existingPreprocessTask(50L, 77L, null);
        when(pipelineTaskDbService.save(any(KbPipelineTask.class))).thenAnswer(inv -> {
            inv.getArgument(0, KbPipelineTask.class).setId(35L);
            return true;
        });

        var response = service.preprocess(10L, 77L, 50L);

        assertEquals("preproc-explicit-v4", response.getStrategyVersion());
        // 显式指定时不走绑定档
        verify(strategyBindingDbService, never()).getByKbAndType(any(), any());
    }

    @Test
    void staleBindingShouldReject40438() {
        // 绑定行指向的策略版本行缺失：不再回退"启用中最新"，一律拒绝
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        when(knowledgeBaseDbService.getActiveById(10L)).thenReturn(knowledgeBase());
        when(strategyBindingDbService.getByKbAndType(10L, PreprocessStrategy.TYPE)).thenReturn(binding());
        when(strategyVersionDbService.getById(66L)).thenReturn(null);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.preprocess(10L, null, 50L));

        assertEquals(ErrorCode.TASK_STRATEGY_MISSING, e.getErrorCode());
        verify(pipelineTaskDbService, never()).save(any());
    }

    @Test
    void inactiveBoundVersionShouldReject40438() {
        // 绑定行指向的策略版本行已停用：与缺失同样拒绝
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        when(knowledgeBaseDbService.getActiveById(10L)).thenReturn(knowledgeBase());
        when(strategyBindingDbService.getByKbAndType(10L, PreprocessStrategy.TYPE)).thenReturn(binding());
        KbPipelineStrategyVersion disabled = boundVersion();
        disabled.setStatus(RowStatus.INACTIVE.name());
        when(strategyVersionDbService.getById(66L)).thenReturn(disabled);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.preprocess(10L, null, 50L));

        assertEquals(ErrorCode.TASK_STRATEGY_MISSING, e.getErrorCode());
        verify(pipelineTaskDbService, never()).save(any());
    }

    @Test
    void unknownStrategyVersionShouldThrow40433() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        when(strategyVersionDbService.getById(999L)).thenReturn(null);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.preprocess(10L, 999L, null));
        assertEquals(ErrorCode.STRATEGY_VERSION_NOT_FOUND, e.getErrorCode());
        verify(pipelineTaskDbService, never()).save(any());
        verify(pipelineProductDbService, never()).getByFileResultIdAndStage(any(), any());
    }

    @Test
    void missingUpstreamProductShouldThrow40436() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        stubBoundStrategy();

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.preprocess(10L, null, null));
        assertEquals(ErrorCode.TASK_UPSTREAM_MISSING, e.getErrorCode());
        verify(pipelineTaskDbService, never()).save(any());
        verify(taskQueue, never()).enqueue(any());
    }

    @Test
    void failedUpstreamTaskShouldThrow40439() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        stubBoundStrategy();
        KbPipelineProduct product = structureProduct();
        when(pipelineProductDbService.getById(50L)).thenReturn(product);
        // 产出任务已失败，产物行却已经落盘（孤儿产物）：下游门禁拒绝消费
        when(pipelineTaskDbService.getById(UPSTREAM_TASK_ID)).thenReturn(upstreamTask(PipelineTaskStatus.FAILED));

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.preprocess(10L, null, 50L));

        assertEquals(ErrorCode.TASK_UPSTREAM_NOT_READY, e.getErrorCode());
        assertTrue(e.getMessage().contains(PipelineTaskStatus.FAILED.name()), e.getMessage());
        verify(pipelineTaskDbService, never()).save(any());
        verify(taskQueue, never()).enqueue(any());
    }

    @Test
    void runningShouldReject40431() {
        stubCommon();
        KbPipelineTask running = new KbPipelineTask();
        running.setId(31L);
        running.setStatus(PipelineTaskStatus.RUNNING.name());
        existingPreprocessTask(50L, 66L, running);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.preprocess(10L, null, 50L));

        assertEquals(ErrorCode.TASK_ALREADY_PENDING, e.getErrorCode());
        // 拒绝文案带任务号与状态：用户能看出是哪条任务占着执行键
        assertTrue(e.getMessage().contains("taskId=31"), e.getMessage());
        assertTrue(e.getMessage().contains(PipelineTaskStatus.RUNNING.name()), e.getMessage());
        verify(pipelineTaskDbService, never()).save(any());
        verify(taskQueue, never()).enqueue(any());
    }

    @Test
    void existingQueuedTaskShouldReject40431() {
        stubCommon();
        KbPipelineTask queued = new KbPipelineTask();
        queued.setId(31L);
        queued.setStatus(PipelineTaskStatus.QUEUED.name());
        existingPreprocessTask(50L, 66L, queued);

        // 执行键命中即拒绝，触发入口不再补投唤醒；QUEUED 补投归启动补偿与低频扫描
        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.preprocess(10L, null, 50L));

        assertEquals(ErrorCode.TASK_ALREADY_PENDING, e.getErrorCode());
        verify(pipelineTaskDbService, never()).save(any());
        verify(taskQueue, never()).enqueue(any());
    }

    @Test
    void succeededTaskShouldReject40431() {
        stubCommon();
        KbPipelineTask succeeded = new KbPipelineTask();
        succeeded.setId(31L);
        succeeded.setStatus(PipelineTaskStatus.SUCCESS.name());
        existingPreprocessTask(50L, 66L, succeeded);

        // 查重只看执行键、不看任务状态
        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.preprocess(10L, null, 50L));

        assertEquals(ErrorCode.TASK_ALREADY_PENDING, e.getErrorCode());
        verify(pipelineTaskDbService, never()).save(any());
        verify(taskQueue, never()).enqueue(any());
    }

    @Test
    void specifiedUpstreamProductShouldBeStoredInTask() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        stubBoundStrategy();
        KbPipelineProduct specified = new KbPipelineProduct();
        specified.setId(88L);
        specified.setTaskId(UPSTREAM_TASK_ID);
        specified.setFileResultId(10L);
        specified.setStage(PipelineStage.STRUCTURE.name());
        when(pipelineProductDbService.getById(88L)).thenReturn(specified);
        existingPreprocessTask(88L, 66L, null);
        when(pipelineTaskDbService.save(any(KbPipelineTask.class))).thenAnswer(inv -> {
            inv.getArgument(0, KbPipelineTask.class).setId(34L);
            return true;
        });

        var response = service.preprocess(10L, null, 88L);

        ArgumentCaptor<KbPipelineTask> captor = ArgumentCaptor.forClass(KbPipelineTask.class);
        verify(pipelineTaskDbService).save(captor.capture());
        assertEquals(88L, captor.getValue().getUpstreamProductId());
        assertEquals(34L, response.getPipelineTaskId());
    }

    @Test
    void detailShouldAssembleTaskStepsStrategyAndElements() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        KbPipelineTask task = new KbPipelineTask();
        task.setId(31L);
        task.setStage(PipelineStage.PREPROCESS.name());
        task.setStatus(PipelineTaskStatus.SUCCESS.name());
        task.setProductId(50L);
        when(pipelineTaskDbService.getByFileResultIdAndStage(10L, PipelineStage.PREPROCESS.name())).thenReturn(task);
        KbPipelineStepLog step = new KbPipelineStepLog();
        step.setStepName("字段标准化");
        step.setStatus("SUCCESS");
        step.setMatchedCount(3);
        step.setChangedCount(2);
        when(stepLogDbService.listByTaskId(31L)).thenReturn(List.of(step));
        KbPipelineProduct product = new KbPipelineProduct();
        product.setArtifactId("abc".repeat(16));
        // 产物行记下对象位置：详情读取按数据源与对象键定位
        product.setStorageType(StorageType.MINIO.getCode());
        product.setStorageSourceId(SOURCE_ID);
        product.setBucket("artifacts");
        when(pipelineProductDbService.getById(50L)).thenReturn(product);
        String json = "{\"strategyVersion\":\"preproc-default-v1\","
                + "\"options\":{\"pageHeaderFooter\":\"MARK\"},"
                + "\"elements\":["
                + "{\"elementId\":\"h-1\",\"type\":\"TITLE\",\"status\":\"NORMAL\",\"displayText\":\"第一章\",\"normalizedText\":\"第一章\"},"
                + "{\"elementId\":\"n-1\",\"type\":\"PARAGRAPH\",\"status\":\"NORMAL\",\"rawText\":\"金额 叁佰万 元整\","
                + "\"displayText\":\"金额 叁佰万 元整\","
                + "\"normalizedText\":\"金额 3000000 元整\",\"normalizedFields\":[{\"field\":\"AMOUNT\",\"value\":\"3000000.00\",\"unit\":\"元\",\"rule\":\"amount-cn-v1\"}],"
                + "\"preprocessTrace\":[{\"rule\":\"amount-cn-v1\",\"field\":\"AMOUNT\",\"action\":\"EXTRACT\",\"before\":\"叁佰万\",\"after\":\"3000000.00\",\"evidence\":\"中文金额命中\"}]},"
                + "{\"elementId\":\"hdr-1\",\"type\":\"HEADER\",\"status\":\"EXCLUDED_HEADER\",\"rawText\":\"页眉文本\",\"displayText\":\"页眉文本\","
                + "\"preprocessTrace\":[{\"rule\":\"header-footer-detect-v1\",\"action\":\"EXCLUDE\",\"before\":\"页眉文本\",\"evidence\":\"多页重复页眉\"}]}]}";
        when(fileStorage.getObject(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(), "artifacts", "abc".repeat(16))))
                .thenReturn(json.getBytes(StandardCharsets.UTF_8));

        PreprocessDetailVO detail = service.preprocessDetail(10L, null);

        assertEquals(31L, detail.getTaskId());
        assertEquals(PipelineTaskStatus.SUCCESS.name(), detail.getStatus());
        assertEquals(1, detail.getSteps().size());
        assertEquals(3, detail.getSteps().getFirst().getMatchedCount());
        assertNotNull(detail.getSummary());
        assertEquals(3, detail.getSummary().getElementCount());
        assertEquals(1, detail.getSummary().getChangedCount());
        assertEquals(1, detail.getSummary().getExcludedCount());
        assertEquals(1, detail.getSummary().getFieldCount());
        assertEquals(3, detail.getElements().size());
        assertEquals(1, detail.getElements().get(1).getFields().size());
        assertEquals("AMOUNT", detail.getElements().get(1).getFields().getFirst().getField());
        // 原文透传 + 处理轨迹透传（非 KEEP 条目）
        assertEquals("金额 叁佰万 元整", detail.getElements().get(1).getRawText());
        assertEquals(1, detail.getElements().get(1).getTrace().size());
        assertEquals("EXTRACT", detail.getElements().get(1).getTrace().getFirst().getAction());
        assertEquals("amount-cn-v1", detail.getElements().get(1).getTrace().getFirst().getRule());
        assertEquals("EXCLUDE", detail.getElements().get(2).getTrace().getFirst().getAction());
        assertEquals("多页重复页眉", detail.getElements().get(2).getTrace().getFirst().getEvidence());
    }

    @Test
    void detailWithoutTaskShouldReturnEmptyElements() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        when(pipelineTaskDbService.getByFileResultIdAndStage(10L, PipelineStage.PREPROCESS.name())).thenReturn(null);

        PreprocessDetailVO detail = service.preprocessDetail(10L, null);

        assertNotNull(detail.getElements());
        assertTrue(detail.getElements().isEmpty());
    }

    @Test
    void detailWithWrongStageTaskShouldReject() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        KbPipelineTask structureTask = new KbPipelineTask();
        structureTask.setId(51L);
        structureTask.setFileResultId(10L);
        structureTask.setStage(PipelineStage.STRUCTURE.name());
        when(pipelineTaskDbService.getById(51L)).thenReturn(structureTask);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.preprocessDetail(10L, 51L));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
    }

    @Test
    void detailWithIncompleteProductLocationShouldReject40455() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        KbPipelineTask task = new KbPipelineTask();
        task.setId(31L);
        task.setStage(PipelineStage.PREPROCESS.name());
        task.setStatus(PipelineTaskStatus.SUCCESS.name());
        task.setProductId(50L);
        when(pipelineTaskDbService.getByFileResultIdAndStage(10L, PipelineStage.PREPROCESS.name())).thenReturn(task);
        // 产物行缺 storageType / bucket：位置不完整，读取路径按「存储后端未配置」拒绝
        KbPipelineProduct product = new KbPipelineProduct();
        product.setArtifactId("abc".repeat(16));
        when(pipelineProductDbService.getById(50L)).thenReturn(product);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.preprocessDetail(10L, null));

        assertEquals(ErrorCode.STORAGE_BACKEND_UNCONFIGURED, e.getErrorCode());
        verify(fileStorage, never()).getObject(any(ObjectRef.class));
    }

    @Test
    void otherUserFileResultShouldReject40401() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        // 归属看的是知识库归属：换一个登录用户，越权与"不存在"同样返回 40401
        SecurityTestSupport.loginOtherUser();

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.preprocess(10L, null, 50L));
        assertEquals(ErrorCode.KB_NOT_FOUND, e.getErrorCode());
    }

    /** 让指定知识库归当前登录用户所有（归属校验要能过） */
    private void boundKnowledgeBase(Long id) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(id);
        kb.setUserId(SecurityTestSupport.VIEWER_ID);
        lenient().when(knowledgeBaseDbService.getActiveById(id)).thenReturn(kb);
    }

}
