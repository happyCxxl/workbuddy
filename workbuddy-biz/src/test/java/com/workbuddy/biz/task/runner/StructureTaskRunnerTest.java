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
import com.workbuddy.common.domain.entity.WbSourceFile;
import com.workbuddy.common.domain.parse.ParseResult;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.domain.storage.StorageRef;
import com.workbuddy.common.domain.structure.AssembleOutcome;
import com.workbuddy.common.domain.structure.UnifiedDocument;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.enums.task.PipelineTaskErrorCode;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.filecenter.provider.StorageRouter;
import com.workbuddy.filecenter.storage.FileStorage;
import com.workbuddy.worker.structure.AssembleContext;
import com.workbuddy.worker.structure.DocumentAssemblerPort;
import com.workbuddy.common.convert.KnowledgeConverterImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;

import com.workbuddy.biz.config.ConfigFixtures;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 统一组装任务执行器单测：成功落库（upstream 链）/ 空树失败 / 上游缺失。
 */
@ExtendWith(MockitoExtension.class)
class StructureTaskRunnerTest {

    /** 档案与产物所在的数据源 */
    private static final Long SOURCE_ID = 1L;

    /** 上游产物的产出任务 ID（门禁按它的状态判定） */
    private static final Long UPSTREAM_TASK_ID = 40L;

    @Mock
    private WbPipelineTaskDbService pipelineTaskDbService;
    @Mock
    private WbPipelineProductDbService pipelineProductDbService;
    @Mock
    private WbPipelineStepLogDbService stepLogDbService;
    @Mock
    private WbFileResultDbService fileResultDbService;
    @Mock
    private WbSourceFileDbService sourceFileDbService;
    @Mock
    private FileStorage fileStorage;
    @Mock
    private StorageRouter storageRouter;
    @Mock
    private DocumentAssemblerPort documentAssembler;

    private StructureTaskRunner runner;

    @BeforeEach
    void setUp() {
        // 存储守卫用真实实例 + mock 依赖：链类型取不到（默认 mock 返回 null）时不拦
        ChainStorageSupport chainStorage = new ChainStorageSupport(fileResultDbService, sourceFileDbService,
                fileStorage, storageRouter);
        runner = new StructureTaskRunner(pipelineTaskDbService, pipelineProductDbService,
                fileStorage, documentAssembler, ConfigFixtures.structure(),
                new StepLogPersistence(stepLogDbService, new KnowledgeConverterImpl()),
                new ProductPersistence(pipelineProductDbService, pipelineTaskDbService, chainStorage, fileStorage
                        , new KnowledgeConverterImpl()),
                new UpstreamProductGate(pipelineTaskDbService));
        // 上游产物的产出任务默认成功：门禁放行；不放行的状态由各用例自行改写（宽松桩）
        lenient().when(pipelineTaskDbService.getById(UPSTREAM_TASK_ID))
                .thenReturn(upstreamTask(PipelineTaskStatus.SUCCESS));
    }

    /** 上游产物的产出任务（环节 PARSE） */
    private WbPipelineTask upstreamTask(PipelineTaskStatus status) {
        WbPipelineTask task = new WbPipelineTask();
        task.setId(UPSTREAM_TASK_ID);
        task.setFileResultId(10L);
        task.setStage(PipelineStage.PARSE.name());
        task.setStatus(status.name());
        return task;
    }

    private WbPipelineTask queuedTask() {
        WbPipelineTask task = new WbPipelineTask();
        task.setId(60L);
        task.setFileResultId(10L);
        task.setUpstreamProductId(50L);
        task.setStage(PipelineStage.STRUCTURE.name());
        task.setStatus(PipelineTaskStatus.QUEUED.name());
        return task;
    }

    private WbPipelineProduct parseProduct() {
        WbPipelineProduct product = new WbPipelineProduct();
        product.setId(50L);
        product.setTaskId(UPSTREAM_TASK_ID);
        product.setArtifactId("abc".repeat(22));
        product.setStorageType(StorageType.MINIO.getCode());
        product.setStorageSourceId(SOURCE_ID);
        product.setBucket("artifacts");
        return product;
    }

    private AssembleOutcome successOutcome() {
        AssembleOutcome outcome = new AssembleOutcome();
        outcome.setSuggestedStatus(PipelineTaskStatus.SUCCESS);
        outcome.setDocument(new UnifiedDocument());
        return outcome;
    }

    /** 链的一环：文件结果 10 → 来源文件 5 */
    private WbFileResult fileResult() {
        WbFileResult fileResult = new WbFileResult();
        fileResult.setId(10L);
        fileResult.setSourceFileId(5L);
        return fileResult;
    }

    /** 链的一环：来源文件 5 → 文件档案 F-88 */
    private WbSourceFile sourceFile() {
        WbSourceFile sourceFile = new WbSourceFile();
        sourceFile.setId(5L);
        sourceFile.setFileId("F-88");
        return sourceFile;
    }

    /**
     * 守护 {@code successPathShouldPersistStructureProductWithUpstreamChain}：该场景行为改动即失败。
     */
    @Test
    void successPathShouldPersistStructureProductWithUpstreamChain() {
        when(pipelineTaskDbService.getById(60L)).thenReturn(queuedTask());
        when(pipelineTaskDbService.claim(60L)).thenReturn(1);
        when(pipelineProductDbService.getById(50L)).thenReturn(parseProduct());
        when(fileStorage.getObject(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(), "artifacts", "abc".repeat(22))))
                .thenReturn(JsonUtil.toJsonStr(new ParseResult()).getBytes(StandardCharsets.UTF_8));
        when(documentAssembler.assemble(any(ParseResult.class), any(AssembleContext.class)))
                .thenReturn(successOutcome());
        when(fileStorage.putObject(any(byte[].class)))
                .thenReturn(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(), "artifacts", "def".repeat(21) + "0"));
        when(pipelineProductDbService.save(any(WbPipelineProduct.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbPipelineProduct.class).setId(70L);
            return true;
        });

        runner.run(60L);

        ArgumentCaptor<WbPipelineProduct> captor = ArgumentCaptor.forClass(WbPipelineProduct.class);
        verify(pipelineProductDbService).save(captor.capture());
        assertEquals(PipelineStage.STRUCTURE.name(), captor.getValue().getStage());
        assertEquals(50L, captor.getValue().getUpstreamProductId());
        assertEquals(10L, captor.getValue().getFileResultId());
        assertEquals("def".repeat(21) + "0", captor.getValue().getArtifactId());
        assertEquals("def".repeat(21) + "0", captor.getValue().getContentHash());
        // 产物行记下对象位置：读取路径据此定位（缺这几段会报 40455）
        assertEquals(StorageType.MINIO.getCode(), captor.getValue().getStorageType());
        assertEquals(SOURCE_ID, captor.getValue().getStorageSourceId());
        assertEquals("artifacts", captor.getValue().getBucket());
        verify(pipelineTaskDbService).finish(60L, PipelineTaskStatus.SUCCESS, null, null);
        verify(pipelineTaskDbService).updateProductId(60L, 70L);
    }

    /**
     * 守护 {@code failedUpstreamTaskShouldFailStructureFailed}：该场景行为改动即失败。
     */
    @Test
    void failedUpstreamTaskShouldFailStructureFailed() {
        when(pipelineTaskDbService.getById(60L)).thenReturn(queuedTask());
        when(pipelineTaskDbService.claim(60L)).thenReturn(1);
        when(pipelineProductDbService.getById(50L)).thenReturn(parseProduct());
        // 上游产物所属任务已失败，产物本体却已落盘：门禁按任务状态拒绝继续
        when(pipelineTaskDbService.getById(UPSTREAM_TASK_ID)).thenReturn(upstreamTask(PipelineTaskStatus.FAILED));

        runner.run(60L);

        ArgumentCaptor<String> errorMsg = ArgumentCaptor.forClass(String.class);
        verify(pipelineTaskDbService).finish(eq(60L), eq(PipelineTaskStatus.FAILED),
                eq(PipelineTaskErrorCode.STRUCTURE_FAILED.name()), errorMsg.capture());
        assertTrue(errorMsg.getValue().contains(PipelineTaskStatus.FAILED.name()), errorMsg.getValue());
        // 门禁在读取产物本体之前：不读对象、不进组装管线
        verify(fileStorage, never()).getObject(any(ObjectRef.class));
        verify(documentAssembler, never()).assemble(any(), any());
    }

    /**
     * 守护 {@code partialSuccessUpstreamTaskShouldPass}：该场景行为改动即失败。
     */
    @Test
    void partialSuccessUpstreamTaskShouldPass() {
        when(pipelineTaskDbService.getById(60L)).thenReturn(queuedTask());
        when(pipelineTaskDbService.claim(60L)).thenReturn(1);
        when(pipelineProductDbService.getById(50L)).thenReturn(parseProduct());
        when(pipelineTaskDbService.getById(UPSTREAM_TASK_ID))
                .thenReturn(upstreamTask(PipelineTaskStatus.PARTIAL_SUCCESS));
        when(fileStorage.getObject(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(), "artifacts", "abc".repeat(22))))
                .thenReturn(JsonUtil.toJsonStr(new ParseResult()).getBytes(StandardCharsets.UTF_8));
        when(documentAssembler.assemble(any(ParseResult.class), any(AssembleContext.class)))
                .thenReturn(successOutcome());
        when(fileStorage.putObject(any(byte[].class)))
                .thenReturn(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(), "artifacts", "def".repeat(21) + "0"));
        when(pipelineProductDbService.save(any(WbPipelineProduct.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbPipelineProduct.class).setId(70L);
            return true;
        });

        runner.run(60L);

        // 部分成功在放行集内：产物照常落库、任务落成功终态
        verify(pipelineProductDbService).save(any(WbPipelineProduct.class));
        verify(pipelineTaskDbService).finish(60L, PipelineTaskStatus.SUCCESS, null, null);
        assertTrue(mockingDetails(pipelineTaskDbService).getInvocations().stream()
                .anyMatch(inv -> "finish".equals(inv.getMethod().getName())));
    }

    /**
     * 守护 {@code mismatchedStorageShouldFailTaskWithoutWritingProduct}：该场景行为改动即失败。
     */
    @Test
    void mismatchedStorageShouldFailTaskWithoutWritingProduct() {
        when(pipelineTaskDbService.getById(60L)).thenReturn(queuedTask());
        when(pipelineTaskDbService.claim(60L)).thenReturn(1);
        when(pipelineProductDbService.getById(50L)).thenReturn(parseProduct());
        when(fileStorage.getObject(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(), "artifacts", "abc".repeat(22))))
                .thenReturn(JsonUtil.toJsonStr(new ParseResult()).getBytes(StandardCharsets.UTF_8));
        when(documentAssembler.assemble(any(ParseResult.class), any(AssembleContext.class)))
                .thenReturn(successOutcome());
        // 链落在数据源 2，当前启用的是数据源 1：写入口拒绝，产物既不入存储也不落库
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        when(sourceFileDbService.getById(5L)).thenReturn(sourceFile());
        when(fileStorage.refOf("F-88")).thenReturn(new StorageRef(StorageType.LOCAL.getCode(), 2L));
        when(storageRouter.currentSourceId()).thenReturn(SOURCE_ID);

        runner.run(60L);

        verify(pipelineTaskDbService).finish(eq(60L), eq(PipelineTaskStatus.FAILED),
                eq(PipelineTaskErrorCode.TASK_STORAGE_MISMATCH.name()), any());
        verify(fileStorage, never()).putObject(any(byte[].class));
        verify(pipelineProductDbService, never()).save(any());
        assertTrue(mockingDetails(pipelineProductDbService).getInvocations().stream()
                .noneMatch(inv -> "save".equals(inv.getMethod().getName())));
    }

    /**
     * 守护 {@code emptyTreeShouldFailWithoutProduct}：该场景行为改动即失败。
     */
    @Test
    void emptyTreeShouldFailWithoutProduct() {
        when(pipelineTaskDbService.getById(60L)).thenReturn(queuedTask());
        when(pipelineTaskDbService.claim(60L)).thenReturn(1);
        when(pipelineProductDbService.getById(50L)).thenReturn(parseProduct());
        when(fileStorage.getObject(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(), "artifacts", "abc".repeat(22))))
                .thenReturn(JsonUtil.toJsonStr(new ParseResult()).getBytes(StandardCharsets.UTF_8));
        AssembleOutcome outcome = new AssembleOutcome();
        outcome.setSuggestedStatus(PipelineTaskStatus.FAILED);
        outcome.setErrorCode(PipelineTaskErrorCode.STRUCTURE_EMPTY.name());
        outcome.setErrorMsg("无任何可组装元素（空树）");
        when(documentAssembler.assemble(any(ParseResult.class), any(AssembleContext.class))).thenReturn(outcome);

        runner.run(60L);

        verify(pipelineTaskDbService).finish(eq(60L), eq(PipelineTaskStatus.FAILED),
                eq(PipelineTaskErrorCode.STRUCTURE_EMPTY.name()), any());
        verify(pipelineProductDbService, never()).save(any());
        verify(fileStorage, never()).putObject(any(byte[].class));
        assertTrue(mockingDetails(fileStorage).getInvocations().stream()
                .noneMatch(inv -> "putObject".equals(inv.getMethod().getName())));
    }

    /**
     * 守护 {@code missingUpstreamProductShouldFailTaskUpstreamMissing}：该场景行为改动即失败。
     */
    @Test
    void missingUpstreamProductShouldFailTaskUpstreamMissing() {
        when(pipelineTaskDbService.getById(60L)).thenReturn(queuedTask());
        when(pipelineTaskDbService.claim(60L)).thenReturn(1);
        when(pipelineProductDbService.getById(50L)).thenReturn(null);

        runner.run(60L);

        // 上游引用缺失与"空树"、"上游不可读"三类失败原因对用户可区分
        verify(pipelineTaskDbService).finish(eq(60L), eq(PipelineTaskStatus.FAILED),
                eq(PipelineTaskErrorCode.TASK_UPSTREAM_MISSING.name()), any());
        verify(fileStorage, never()).getObject(any());
        assertTrue(mockingDetails(fileStorage).getInvocations().stream()
                .noneMatch(inv -> "getObject".equals(inv.getMethod().getName())));
    }

    /**
     * 守护 {@code unexpectedFailureShouldUseClassNameInsteadOfNull}：该场景行为改动即失败。
     */
    @Test
    void unexpectedFailureShouldUseClassNameInsteadOfNull() {
        when(pipelineTaskDbService.getById(60L)).thenReturn(queuedTask());
        when(pipelineTaskDbService.claim(60L)).thenReturn(1);
        when(pipelineProductDbService.getById(50L)).thenReturn(parseProduct());
        when(fileStorage.getObject(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(), "artifacts", "abc".repeat(22))))
                .thenReturn(JsonUtil.toJsonStr(new ParseResult()).getBytes(StandardCharsets.UTF_8));
        when(documentAssembler.assemble(any(ParseResult.class), any(AssembleContext.class)))
                .thenThrow(new IllegalStateException());

        runner.run(60L);

        ArgumentCaptor<String> errorMsg = ArgumentCaptor.forClass(String.class);
        verify(pipelineTaskDbService).finish(eq(60L), eq(PipelineTaskStatus.FAILED),
                eq(PipelineTaskErrorCode.STRUCTURE_FAILED.name()), errorMsg.capture());
        // 无消息的异常不落 "null"，退化为类名，不看日志也能判断异常类型
        assertEquals("IllegalStateException", errorMsg.getValue());
    }

    /**
     * 守护 {@code knowledgeExceptionOnAssembleShouldFailUpstreamUnreadable}：该场景行为改动即失败。
     */
    @Test
    void knowledgeExceptionOnAssembleShouldFailUpstreamUnreadable() {
        when(pipelineTaskDbService.getById(60L)).thenReturn(queuedTask());
        when(pipelineTaskDbService.claim(60L)).thenReturn(1);
        when(pipelineProductDbService.getById(50L)).thenReturn(parseProduct());
        when(fileStorage.getObject(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(), "artifacts", "abc".repeat(22))))
                .thenReturn(JsonUtil.toJsonStr(new ParseResult()).getBytes(StandardCharsets.UTF_8));
        when(documentAssembler.assemble(any(ParseResult.class), any(AssembleContext.class)))
                .thenThrow(new KnowledgeException(ErrorCode.FILE_NOT_FOUND));

        runner.run(60L);

        ArgumentCaptor<String> errorMsg = ArgumentCaptor.forClass(String.class);
        verify(pipelineTaskDbService).finish(eq(60L), eq(PipelineTaskStatus.FAILED),
                eq(PipelineTaskErrorCode.STRUCTURE_UPSTREAM_UNREADABLE.name()), errorMsg.capture());
        // 业务异常（上游文件已不存在）按"上游不可读"归口，且直接用它的原因文案
        assertEquals(ErrorCode.FILE_NOT_FOUND.getMessage(), errorMsg.getValue());
    }

    /**
     * 守护 {@code unreadableUpstreamWithBlankMessageShouldStillNameTheCause}：该场景行为改动即失败。
     */
    @Test
    void unreadableUpstreamWithBlankMessageShouldStillNameTheCause() {
        when(pipelineTaskDbService.getById(60L)).thenReturn(queuedTask());
        when(pipelineTaskDbService.claim(60L)).thenReturn(1);
        when(pipelineProductDbService.getById(50L)).thenReturn(parseProduct());
        when(fileStorage.getObject(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(),
                "artifacts", "abc".repeat(22)))).thenThrow(new IllegalStateException());

        runner.run(60L);

        ArgumentCaptor<String> errorMsg = ArgumentCaptor.forClass(String.class);
        verify(pipelineTaskDbService).finish(eq(60L), eq(PipelineTaskStatus.FAILED),
                eq(PipelineTaskErrorCode.STRUCTURE_UPSTREAM_UNREADABLE.name()), errorMsg.capture());
        assertEquals("上游解析产物读取失败: IllegalStateException", errorMsg.getValue());
    }
}
