package com.workbuddy.biz.task.runner;

import com.workbuddy.biz.service.db.WbFileResultDbService;
import com.workbuddy.biz.service.db.WbPipelineProductDbService;
import com.workbuddy.biz.service.db.WbPipelineStepLogDbService;
import com.workbuddy.biz.service.db.WbPipelineTaskDbService;
import com.workbuddy.biz.service.db.WbSourceFileDbService;
import com.workbuddy.biz.service.support.access.ChainStorageSupport;
import com.workbuddy.biz.task.runner.persistence.ProductPersistence;
import com.workbuddy.biz.task.runner.persistence.StepLogPersistence;
import com.workbuddy.biz.task.UpstreamProductGate;
import com.workbuddy.common.domain.entity.WbFileResult;
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.domain.preprocess.PreprocessOutcome;
import com.workbuddy.common.domain.preprocess.PreprocessView;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.domain.structure.DocumentInfo;
import com.workbuddy.common.domain.structure.UnifiedDocument;
import com.workbuddy.common.domain.structure.UnifiedElement;
import com.workbuddy.common.domain.task.StepLogInfo;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.enums.structure.UnifiedElementType;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.enums.task.PipelineTaskErrorCode;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.filecenter.provider.StorageRouter;
import com.workbuddy.filecenter.storage.FileStorage;
import com.workbuddy.worker.preprocessing.PreprocessContext;
import com.workbuddy.worker.preprocessing.PreprocessProperties;
import com.workbuddy.worker.preprocessing.PreprocessorPort;
import com.workbuddy.worker.preprocessing.strategy.PreprocessStrategyParser;
import com.workbuddy.common.convert.KnowledgeConverterImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 预处理任务执行器单测：成功落库（upstream 链 + 9 步日志）/ 上游缺失 PREPROCESS_EMPTY / 管线失败。
 */
@ExtendWith(MockitoExtension.class)
class PreprocessTaskRunnerTest {

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
    private FileStorage fileStorage;
    @Mock
    private StorageRouter storageRouter;
    @Mock
    private PreprocessorPort preprocessor;

    private PreprocessTaskRunner runner;

    @BeforeEach
    void setUp() {
        // 存储守卫用真实实例 + mock 依赖：链类型取不到（默认 mock 返回 null）时不拦
        ChainStorageSupport chainStorage = new ChainStorageSupport(fileResultDbService, sourceFileDbService,
                fileStorage, storageRouter);
        runner = new PreprocessTaskRunner(pipelineTaskDbService, fileResultDbService,
                pipelineProductDbService, fileStorage, preprocessor,
                new PreprocessProperties(), new PreprocessStrategyParser(new PreprocessProperties()),
                new StepLogPersistence(stepLogDbService, new KnowledgeConverterImpl()),
                new ProductPersistence(pipelineProductDbService, pipelineTaskDbService, chainStorage, fileStorage
                        , new KnowledgeConverterImpl()),
                new UpstreamProductGate(pipelineTaskDbService));
        // 上游产物的产出任务默认成功：门禁放行；不放行的状态由各用例自行改写（宽松桩）
        lenient().when(pipelineTaskDbService.getById(UPSTREAM_TASK_ID))
                .thenReturn(upstreamTask(PipelineTaskStatus.SUCCESS));
    }

    /** 上游产物的产出任务（环节 STRUCTURE） */
    private WbPipelineTask upstreamTask(PipelineTaskStatus status) {
        WbPipelineTask task = new WbPipelineTask();
        task.setId(UPSTREAM_TASK_ID);
        task.setFileResultId(10L);
        task.setStage(PipelineStage.STRUCTURE.name());
        task.setStatus(status.name());
        return task;
    }

    private WbPipelineTask queuedTask() {
        WbPipelineTask task = new WbPipelineTask();
        task.setId(60L);
        task.setFileResultId(10L);
        task.setUpstreamProductId(50L);
        // 策略快照按任务行上的快照取（触发时固定）；空对象即解析器默认参数
        task.setStrategySnapshot("{}");
        task.setStage(PipelineStage.PREPROCESS.name());
        task.setStatus(PipelineTaskStatus.QUEUED.name());
        return task;
    }

    private WbPipelineProduct structureProduct() {
        WbPipelineProduct product = new WbPipelineProduct();
        product.setId(50L);
        product.setTaskId(UPSTREAM_TASK_ID);
        product.setArtifactId("abc".repeat(22));
        product.setStorageType(StorageType.MINIO.getCode());
        product.setStorageSourceId(SOURCE_ID);
        product.setBucket("artifacts");
        return product;
    }

    private UnifiedDocument document() {
        UnifiedDocument document = new UnifiedDocument();
        DocumentInfo info = new DocumentInfo();
        info.setDocumentId("doc-10");
        document.setDocumentInfo(info);
        List<UnifiedElement> elements = new ArrayList<>();
        UnifiedElement element = new UnifiedElement();
        element.setId("n-1");
        element.setType(UnifiedElementType.PARAGRAPH.name());
        element.setText("正文内容");
        elements.add(element);
        document.setElements(elements);
        return document;
    }

    private PreprocessOutcome successOutcome() {
        PreprocessOutcome outcome = new PreprocessOutcome();
        outcome.setSuggestedStatus(PipelineTaskStatus.SUCCESS);
        PreprocessView view = new PreprocessView();
        view.setViewId("pv-doc-10-preproc-default-v1");
        outcome.setView(view);
        List<StepLogInfo> steps = new ArrayList<>();
        for (String name : List.of("编码规范化", "段落/表格文本整理", "页眉页脚处置", "目录处置",
                "重复处置", "字段规范化", "噪声处置", "自定义规则", "视图组装")) {
            StepLogInfo step = new StepLogInfo();
            step.setStepName(name);
            step.setStatus("SUCCESS");
            step.setWarningCount(0);
            step.setMatchedCount(1);
            step.setChangedCount(0);
            steps.add(step);
        }
        outcome.setStepLogs(steps);
        return outcome;
    }

    private WbFileResult fileResult() {
        WbFileResult fileResult = new WbFileResult();
        fileResult.setId(10L);
        fileResult.setSourceFileId(5L);
        return fileResult;
    }

    /**
     * 守护 {@code successPathShouldPersistViewProductWithUpstreamChainAndNineSteps}：该场景行为改动即失败。
     */
    @Test
    void successPathShouldPersistViewProductWithUpstreamChainAndNineSteps() {
        when(pipelineTaskDbService.getById(60L)).thenReturn(queuedTask());
        when(pipelineTaskDbService.claim(60L)).thenReturn(1);
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        when(pipelineProductDbService.getById(50L)).thenReturn(structureProduct());
        when(fileStorage.getObject(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(), "artifacts", "abc".repeat(22))))
                .thenReturn(Objects.requireNonNull(JsonUtil.toJsonStr(document())).getBytes(StandardCharsets.UTF_8));
        when(preprocessor.preprocess(any(PreprocessContext.class))).thenReturn(successOutcome());
        when(fileStorage.putObject(any(byte[].class)))
                .thenReturn(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(), "artifacts", "def".repeat(21) + "0"));
        when(pipelineProductDbService.save(any(WbPipelineProduct.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbPipelineProduct.class).setId(70L);
            return true;
        });

        runner.run(60L);

        ArgumentCaptor<PreprocessContext> contextCaptor = ArgumentCaptor.forClass(PreprocessContext.class);
        verify(preprocessor).preprocess(contextCaptor.capture());
        assertEquals("doc-10", contextCaptor.getValue().getDocument().getDocumentInfo().getDocumentId());
        assertNotNull(contextCaptor.getValue().getStrategy());

        ArgumentCaptor<WbPipelineProduct> productCaptor = ArgumentCaptor.forClass(WbPipelineProduct.class);
        verify(pipelineProductDbService).save(productCaptor.capture());
        assertEquals(PipelineStage.PREPROCESS.name(), productCaptor.getValue().getStage());
        assertEquals(50L, productCaptor.getValue().getUpstreamProductId());
        assertEquals(10L, productCaptor.getValue().getFileResultId());
        assertNotNull(productCaptor.getValue().getCapabilitySnapshot());
        // 产物行记下对象位置：读取路径据此定位（缺这两段会报 40455）
        assertEquals(StorageType.MINIO.getCode(), productCaptor.getValue().getStorageType());
        assertEquals("artifacts", productCaptor.getValue().getBucket());

        verify(pipelineTaskDbService).finish(60L, PipelineTaskStatus.SUCCESS, null, null);
        verify(pipelineTaskDbService).updateProductId(60L, 70L);
        verify(stepLogDbService, times(9)).save(any());
    }

    /**
     * 守护 {@code specifiedUpstreamProductShouldBePreferred}：该场景行为改动即失败。
     */
    @Test
    void specifiedUpstreamProductShouldBePreferred() {
        WbPipelineTask task = queuedTask();
        task.setUpstreamProductId(88L);
        when(pipelineTaskDbService.getById(60L)).thenReturn(task);
        when(pipelineTaskDbService.claim(60L)).thenReturn(1);
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        WbPipelineProduct specified = structureProduct();
        specified.setId(88L);
        specified.setArtifactId("spec".repeat(16));
        when(pipelineProductDbService.getById(88L)).thenReturn(specified);
        when(fileStorage.getObject(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(),
                "artifacts", "spec".repeat(16))))
                .thenReturn(Objects.requireNonNull(JsonUtil.toJsonStr(document())).getBytes(StandardCharsets.UTF_8));
        when(preprocessor.preprocess(any(PreprocessContext.class))).thenReturn(successOutcome());
        when(fileStorage.putObject(any(byte[].class)))
                .thenReturn(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(), "artifacts", "out".repeat(16)));
        when(pipelineProductDbService.save(any(WbPipelineProduct.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbPipelineProduct.class).setId(70L);
            return true;
        });

        runner.run(60L);

        verify(fileStorage).getObject(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(),
                "artifacts", "spec".repeat(16)));
        verify(pipelineProductDbService, never()).getByFileResultIdAndStage(any(), any());
        ArgumentCaptor<WbPipelineProduct> productCaptor = ArgumentCaptor.forClass(WbPipelineProduct.class);
        verify(pipelineProductDbService).save(productCaptor.capture());
        assertEquals(88L, productCaptor.getValue().getUpstreamProductId());
    }

    /**
     * 守护 {@code missingUpstreamProductShouldFailTaskUpstreamMissing}：该场景行为改动即失败。
     */
    @Test
    void missingUpstreamProductShouldFailTaskUpstreamMissing() {
        when(pipelineTaskDbService.getById(60L)).thenReturn(queuedTask());
        when(pipelineTaskDbService.claim(60L)).thenReturn(1);
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        when(pipelineProductDbService.getById(50L)).thenReturn(null);

        runner.run(60L);

        verify(pipelineTaskDbService).finish(eq(60L), eq(PipelineTaskStatus.FAILED),
                eq(PipelineTaskErrorCode.TASK_UPSTREAM_MISSING.name()), any());
        verify(fileStorage, never()).putObject(any(byte[].class));
        assertTrue(mockingDetails(fileStorage).getInvocations().stream()
                .noneMatch(inv -> "putObject".equals(inv.getMethod().getName())));
    }

    /**
     * 守护 {@code failedUpstreamTaskShouldFailPreprocessFailed}：该场景行为改动即失败。
     */
    @Test
    void failedUpstreamTaskShouldFailPreprocessFailed() {
        when(pipelineTaskDbService.getById(60L)).thenReturn(queuedTask());
        when(pipelineTaskDbService.claim(60L)).thenReturn(1);
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        when(pipelineProductDbService.getById(50L)).thenReturn(structureProduct());
        // 上游产物所属任务已失败，产物本体却已落盘：门禁按任务状态拒绝继续
        when(pipelineTaskDbService.getById(UPSTREAM_TASK_ID)).thenReturn(upstreamTask(PipelineTaskStatus.FAILED));

        runner.run(60L);

        ArgumentCaptor<String> errorMsg = ArgumentCaptor.forClass(String.class);
        verify(pipelineTaskDbService).finish(eq(60L), eq(PipelineTaskStatus.FAILED),
                eq(PipelineTaskErrorCode.PREPROCESS_FAILED.name()), errorMsg.capture());
        assertTrue(errorMsg.getValue().contains(PipelineTaskStatus.FAILED.name()), errorMsg.getValue());
        // 门禁在读取产物本体之前：不读对象、不进预处理管线
        verify(fileStorage, never()).getObject(any(ObjectRef.class));
        verify(preprocessor, never()).preprocess(any());
    }

    /**
     * 守护 {@code corruptStrategySnapshotShouldFailPreprocessFailed}：该场景行为改动即失败。
     */
    @Test
    void corruptStrategySnapshotShouldFailPreprocessFailed() {
        // 快照坏（"{oops"）：解析抛异常被显式接住，任务落 PREPROCESS_FAILED 且文案干净可辨识
        WbPipelineTask task = queuedTask();
        task.setStrategySnapshot("{oops");
        when(pipelineTaskDbService.getById(60L)).thenReturn(task);
        when(pipelineTaskDbService.claim(60L)).thenReturn(1);
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        when(pipelineProductDbService.getById(50L)).thenReturn(structureProduct());

        runner.run(60L);

        verify(pipelineTaskDbService).finish(eq(60L), eq(PipelineTaskStatus.FAILED),
                eq(PipelineTaskErrorCode.PREPROCESS_FAILED.name()),
                eq("任务未带预处理策略快照或快照已损坏，无法预处理"));
        // 守卫在读取上游产物本体之前：坏快照不读对象、不进管线
        verify(fileStorage, never()).getObject(any(ObjectRef.class));
        verify(preprocessor, never()).preprocess(any());
        assertTrue(mockingDetails(preprocessor).getInvocations().stream()
                .noneMatch(inv -> "preprocess".equals(inv.getMethod().getName())));
    }

    /**
     * 守护 {@code failedOutcomeShouldFinishFailedWithoutProduct}：该场景行为改动即失败。
     */
    @Test
    void failedOutcomeShouldFinishFailedWithoutProduct() {
        when(pipelineTaskDbService.getById(60L)).thenReturn(queuedTask());
        when(pipelineTaskDbService.claim(60L)).thenReturn(1);
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        when(pipelineProductDbService.getById(50L)).thenReturn(structureProduct());
        when(fileStorage.getObject(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(), "artifacts", "abc".repeat(22))))
                .thenReturn(Objects.requireNonNull(JsonUtil.toJsonStr(document())).getBytes(StandardCharsets.UTF_8));
        PreprocessOutcome outcome = new PreprocessOutcome();
        outcome.setSuggestedStatus(PipelineTaskStatus.FAILED);
        outcome.setErrorCode(PipelineTaskErrorCode.PREPROCESS_EMPTY.name());
        outcome.setErrorMsg("上游统一结构无任何可处理元素");
        when(preprocessor.preprocess(any(PreprocessContext.class))).thenReturn(outcome);

        runner.run(60L);

        verify(pipelineTaskDbService).finish(eq(60L), eq(PipelineTaskStatus.FAILED),
                eq(PipelineTaskErrorCode.PREPROCESS_EMPTY.name()), any());
        verify(fileStorage, never()).putObject(any(byte[].class));
        verify(pipelineProductDbService, never()).save(any());
        assertTrue(mockingDetails(pipelineProductDbService).getInvocations().stream()
                .noneMatch(inv -> "save".equals(inv.getMethod().getName())));
    }
}
