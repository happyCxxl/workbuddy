package com.workbuddy.biz.task.runner;

import com.workbuddy.biz.service.db.WbFileResultDbService;
import com.workbuddy.biz.service.db.WbPipelineProductDbService;
import com.workbuddy.biz.service.db.WbPipelineStepLogDbService;
import com.workbuddy.biz.service.db.WbPipelineTaskDbService;
import com.workbuddy.biz.service.db.WbSourceFileDbService;
import com.workbuddy.biz.service.support.access.ChainStorageSupport;
import com.workbuddy.biz.task.runner.persistence.ProductPersistence;
import com.workbuddy.biz.task.runner.persistence.StepLogPersistence;
import com.workbuddy.common.domain.entity.WbFileResult;
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.domain.entity.WbSourceFile;
import com.workbuddy.common.domain.parse.ParseOutcome;
import com.workbuddy.common.domain.parse.ParseResult;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.domain.task.StepLogInfo;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.enums.task.PipelineTaskErrorCode;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.WorkbuddyException;
import com.workbuddy.filecenter.provider.StorageRouter;
import com.workbuddy.filecenter.storage.FileStorage;
import com.workbuddy.worker.parser.ParseContext;
import com.workbuddy.worker.parser.ParsePipeline;
import com.workbuddy.common.convert.knowledge.PipelineConverterImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import com.workbuddy.biz.config.ConfigFixtures;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 解析任务执行器单测：领任务防重、成功/失败回写、产物与子步骤落库。
 */
@ExtendWith(MockitoExtension.class)
class ParseTaskRunnerTest {

    @Mock
    private WbPipelineTaskDbService pipelineTaskDbService;

    /** 档案与产物所在的数据源 */
    private static final Long SOURCE_ID = 1L;

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
    private ParsePipeline parsePipeline;

    private ParseTaskRunner runner;

    @BeforeEach
    void setUp() {
        // 存储守卫用真实实例 + mock 依赖：链类型取不到（默认 mock 返回 null）时不拦
        ChainStorageSupport chainStorage = new ChainStorageSupport(fileResultDbService, sourceFileDbService,
                fileStorage, storageRouter);
        runner = new ParseTaskRunner(pipelineTaskDbService, fileResultDbService, sourceFileDbService,
                new ProductPersistence(pipelineProductDbService, pipelineTaskDbService, chainStorage, fileStorage
                        , new PipelineConverterImpl()),
                fileStorage, parsePipeline,
                ConfigFixtures.parse(), new StepLogPersistence(stepLogDbService, new PipelineConverterImpl()));
    }

    private WbPipelineTask queuedTask() {
        WbPipelineTask task = new WbPipelineTask();
        task.setId(20L);
        task.setFileResultId(10L);
        task.setStatus(PipelineTaskStatus.QUEUED.name());
        return task;
    }

    private void stubFileChain() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        when(sourceFileDbService.getById(5L)).thenReturn(sourceFile());
        when(fileStorage.open("F-88")).thenReturn(new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)));
    }

    private WbFileResult fileResult() {
        WbFileResult fileResult = new WbFileResult();
        fileResult.setId(10L);
        fileResult.setSourceFileId(5L);
        return fileResult;
    }

    private WbSourceFile sourceFile() {
        WbSourceFile sourceFile = new WbSourceFile();
        sourceFile.setFileId("F-88");
        sourceFile.setFileName("招标文件.pdf");
        sourceFile.setSha256("abc");
        sourceFile.setMimeType("application/pdf");
        return sourceFile;
    }

    private ParseOutcome successOutcome() {
        ParseOutcome outcome = new ParseOutcome();
        outcome.setSuggestedStatus(PipelineTaskStatus.SUCCESS);
        outcome.setUnitCount(3);
        ParseResult parseResult = new ParseResult();
        parseResult.setResultId(10L);
        outcome.setParseResult(parseResult);
        StepLogInfo step = new StepLogInfo();
        step.setStepName("原生解析");
        step.setStatus("SUCCESS");
        step.setStartedAt(LocalDateTime.now());
        step.setFinishedAt(LocalDateTime.now());
        step.setDuration(10);
        outcome.setStepLogs(List.of(step));
        return outcome;
    }

    /**
     * 守护 {@code nonQueuedTaskShouldSkip}：该场景行为改动即失败。
     */
    @Test
    void nonQueuedTaskShouldSkip() {
        WbPipelineTask task = queuedTask();
        task.setStatus(PipelineTaskStatus.SUCCESS.name());
        when(pipelineTaskDbService.getById(20L)).thenReturn(task);

        runner.run(20L);

        verify(pipelineTaskDbService, never()).claim(any());
        assertTrue(mockingDetails(pipelineTaskDbService).getInvocations().stream()
                .noneMatch(inv -> "claim".equals(inv.getMethod().getName())));
    }

    /**
     * 守护 {@code claimLostShouldSkip}：该场景行为改动即失败。
     */
    @Test
    void claimLostShouldSkip() {
        when(pipelineTaskDbService.getById(20L)).thenReturn(queuedTask());
        when(pipelineTaskDbService.claim(20L)).thenReturn(0);

        runner.run(20L);

        verify(fileResultDbService, never()).getById(any());
        assertTrue(mockingDetails(fileResultDbService).getInvocations().stream()
                .noneMatch(inv -> "getById".equals(inv.getMethod().getName())));
    }

    /**
     * 守护 {@code successPathShouldPersistProductAndFinish}：该场景行为改动即失败。
     */
    @Test
    void successPathShouldPersistProductAndFinish() {
        when(pipelineTaskDbService.getById(20L)).thenReturn(queuedTask());
        when(pipelineTaskDbService.claim(20L)).thenReturn(1);
        stubFileChain();
        when(parsePipeline.run(any(ParseContext.class))).thenReturn(successOutcome());
        when(fileStorage.putObject(any(byte[].class)))
                .thenReturn(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(), "artifacts", "9f2c".repeat(16)));
        when(pipelineProductDbService.save(any(WbPipelineProduct.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbPipelineProduct.class).setId(40L);
            return true;
        });

        runner.run(20L);

        ArgumentCaptor<WbPipelineProduct> captor = ArgumentCaptor.forClass(WbPipelineProduct.class);
        verify(pipelineProductDbService).save(captor.capture());
        assertEquals(10L, captor.getValue().getFileResultId());
        assertEquals("PARSE", captor.getValue().getStage());
        assertEquals("9f2c".repeat(16), captor.getValue().getArtifactId());
        assertEquals("9f2c".repeat(16), captor.getValue().getContentHash());
        // 产物行记下对象位置：读取路径据此定位（缺这几段会报 40455）
        assertEquals(StorageType.MINIO.getCode(), captor.getValue().getStorageType());
        assertEquals(SOURCE_ID, captor.getValue().getStorageSourceId());
        assertEquals("artifacts", captor.getValue().getBucket());
        verify(pipelineTaskDbService).finish(20L, PipelineTaskStatus.SUCCESS, null, null);
        verify(pipelineTaskDbService).updateProductId(20L, 40L);
        // 手动逐环节口径：PARSE 完成后停在终态，不再自动登记 STRUCTURE
        verify(pipelineTaskDbService, never()).save(any(WbPipelineTask.class));
        verify(stepLogDbService).save(any());
    }

    /**
     * 守护 {@code scannedOutcomeShouldFinishFailedWithoutProduct}：该场景行为改动即失败。
     */
    @Test
    void scannedOutcomeShouldFinishFailedWithoutProduct() {
        when(pipelineTaskDbService.getById(20L)).thenReturn(queuedTask());
        when(pipelineTaskDbService.claim(20L)).thenReturn(1);
        stubFileChain();
        ParseOutcome outcome = new ParseOutcome();
        outcome.setSuggestedStatus(PipelineTaskStatus.FAILED);
        outcome.setErrorCode(PipelineTaskErrorCode.SCANNED_UNSUPPORTED.name());
        outcome.setErrorMsg("扫描件暂不支持（OCR 预留）");
        when(parsePipeline.run(any(ParseContext.class))).thenReturn(outcome);

        runner.run(20L);

        verify(pipelineTaskDbService).finish(20L, PipelineTaskStatus.FAILED,
                PipelineTaskErrorCode.SCANNED_UNSUPPORTED.name(), "扫描件暂不支持（OCR 预留）");
        verify(pipelineProductDbService, never()).save(any());
        assertTrue(mockingDetails(pipelineProductDbService).getInvocations().stream()
                .noneMatch(inv -> "save".equals(inv.getMethod().getName())));
    }

    /**
     * 守护 {@code missingFileResultShouldFail}：该场景行为改动即失败。
     */
    @Test
    void missingFileResultShouldFail() {
        when(pipelineTaskDbService.getById(20L)).thenReturn(queuedTask());
        when(pipelineTaskDbService.claim(20L)).thenReturn(1);
        when(fileResultDbService.getById(10L)).thenReturn(null);

        runner.run(20L);

        verify(pipelineTaskDbService).finish(eq(20L), eq(PipelineTaskStatus.FAILED),
                eq(PipelineTaskErrorCode.PARSE_FAILED.name()), any());
        assertTrue(mockingDetails(pipelineTaskDbService).getInvocations().stream()
                .anyMatch(inv -> "finish".equals(inv.getMethod().getName())));
    }

    /**
     * 守护 {@code fileGoneShouldFailWithClearReason}：该场景行为改动即失败。
     */
    @Test
    void fileGoneShouldFailWithClearReason() {
        when(pipelineTaskDbService.getById(20L)).thenReturn(queuedTask());
        when(pipelineTaskDbService.claim(20L)).thenReturn(1);
        stubFileChain();
        when(parsePipeline.run(any(ParseContext.class)))
                .thenThrow(new WorkbuddyException(ErrorCode.FILE_NOT_FOUND));

        runner.run(20L);

        verify(pipelineTaskDbService).finish(eq(20L), eq(PipelineTaskStatus.FAILED),
                eq(PipelineTaskErrorCode.PARSE_FAILED.name()), any());
        assertTrue(mockingDetails(pipelineTaskDbService).getInvocations().stream()
                .anyMatch(inv -> "finish".equals(inv.getMethod().getName())));
    }
}
