package com.workbuddy.biz.service.impl;

import com.workbuddy.biz.service.db.WbFileResultDbService;
import com.workbuddy.biz.service.db.WbKnowledgeBaseDbService;
import com.workbuddy.biz.service.db.WbPipelineTaskDbService;
import com.workbuddy.biz.service.db.WbSourceFileDbService;
import com.workbuddy.biz.service.db.WbSubmitLogDbService;
import com.workbuddy.biz.service.support.access.KnowledgeBaseAccessSupport;
import com.workbuddy.biz.testkit.TransactionTestSupport;
import com.workbuddy.common.domain.entity.WbFileResult;
import com.workbuddy.common.domain.entity.WbKnowledgeBase;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.domain.entity.WbSourceFile;
import com.workbuddy.common.domain.entity.WbSubmitLog;
import com.workbuddy.common.domain.input.FileValidationResult;
import com.workbuddy.common.enums.input.FileValidationFailReason;
import com.workbuddy.common.dto.request.input.FileSubmitDTO;
import com.workbuddy.common.dto.response.input.FileSubmitVO;
import com.workbuddy.common.enums.input.SubmitStatus;
import com.workbuddy.common.enums.knowledge.KnowledgeBaseStatus;
import com.workbuddy.common.enums.user.UserRole;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.WorkbuddyException;
import com.workbuddy.filecenter.provider.StorageRouter;
import com.workbuddy.filecenter.service.FileService;
import com.workbuddy.filecenter.storage.FileStorage;
import com.workbuddy.common.convert.file.FileConverterImpl;
import com.workbuddy.common.convert.system.SystemConverterImpl;
import com.workbuddy.common.convert.knowledge.InputConverterImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文件提交失败分支单测：校验失败 / 文件不存在 / 归属与幂等键校验。
 */
class KnowledgeBaseFileSubmitFailureTest extends KnowledgeBaseFileTestFixture {

    private WbKnowledgeBaseDbService knowledgeBaseDbService;

    private WbFileResultDbService kbFileResultDbService;

    private WbSourceFileDbService sourceFileDbService;

    private WbSubmitLogDbService submitLogDbService;

    private WbPipelineTaskDbService pipelineTaskDbService;

    private FileService fileService;

    private FileStorage fileStorage;

    private KnowledgeBaseFileServiceImpl service;

    /**
     * 每个用例重建提交服务，并默认以普通用户登录。
     */
    @BeforeEach
    void setUp() {
        knowledgeBaseDbService = mock(WbKnowledgeBaseDbService.class);
        kbFileResultDbService = mock(WbFileResultDbService.class);
        sourceFileDbService = mock(WbSourceFileDbService.class);
        submitLogDbService = mock(WbSubmitLogDbService.class);
        pipelineTaskDbService = mock(WbPipelineTaskDbService.class);
        fileService = mock(FileService.class);
        fileStorage = mock(FileStorage.class);
        service = new KnowledgeBaseFileServiceImpl(submitLogDbService, kbFileResultDbService,
                sourceFileDbService, pipelineTaskDbService, fileStorage, fileService, mock(StorageRouter.class),
                TransactionTestSupport.template(), new InputConverterImpl(),
                new KnowledgeBaseAccessSupport(knowledgeBaseDbService)
                        , new FileConverterImpl()
                        , new SystemConverterImpl());
        login(ME, UserRole.USER);
    }

    /**
     * 校验失败时的 FAIL 记录：文件名校验阶段能取到就落库。
     *
     * <p>守护 {@code recordFailAndReturn} 的字段搬运：缺 fileId / requestId / 空 sha256 占位即失败。
     */
    @Test
    void submitFailLogShouldCarryAllFields() {
        when(submitLogDbService.getByRequestId("req-1")).thenReturn(null);
        when(knowledgeBaseDbService.getActiveById(1L)).thenReturn(activeKb());
        when(fileService.validate("88"))
                .thenReturn(FileValidationResult.fail(FileValidationFailReason.FILE_ENCRYPTED));
        when(fileStorage.metadata("88")).thenReturn(metadata());
        when(submitLogDbService.save(any(WbSubmitLog.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbSubmitLog.class).setId(30L);
            return true;
        });

        service.submit(1L, request());

        ArgumentCaptor<WbSubmitLog> captor = ArgumentCaptor.forClass(WbSubmitLog.class);
        verify(submitLogDbService).save(captor.capture());
        WbSubmitLog saved = captor.getValue();
        assertEquals(1L, saved.getKnowledgeBaseId());
        assertEquals("req-1", saved.getRequestId());
        assertEquals("88", saved.getFileId());
        assertEquals("招标文件.pdf", saved.getFileName());
        assertEquals("", saved.getSha256());
        assertEquals(SubmitStatus.FAIL.name(), saved.getStatus());
        assertEquals(FileValidationFailReason.FILE_ENCRYPTED.name(), saved.getFailReason());
        assertEquals(Long.valueOf(ME), saved.getUserId());
    }

    /**
     * 校验失败且元数据不可得：文件名存空串占位，不因取不到元数据再次失败。
     *
     * <p>守护 {@code recordFailAndReturn} 的兜底分支：抛异常会让"文件不存在"连失败日志都留不下。
     */
    @Test
    void submitFailLogShouldTolerateMissingMetadata() {
        when(submitLogDbService.getByRequestId("req-1")).thenReturn(null);
        when(knowledgeBaseDbService.getActiveById(1L)).thenReturn(activeKb());
        when(fileService.validate("88"))
                .thenReturn(FileValidationResult.fail(FileValidationFailReason.FILE_NOT_FOUND));
        when(fileStorage.metadata("88")).thenThrow(new IllegalStateException("对象不存在"));
        when(submitLogDbService.save(any(WbSubmitLog.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbSubmitLog.class).setId(30L);
            return true;
        });

        service.submit(1L, request());

        ArgumentCaptor<WbSubmitLog> captor = ArgumentCaptor.forClass(WbSubmitLog.class);
        verify(submitLogDbService).save(captor.capture());
        assertEquals("", captor.getValue().getFileName());
        assertEquals(SubmitStatus.FAIL.name(), captor.getValue().getStatus());
    }

    /**
     * 校验失败：只写 FAIL 日志，不建结果。
     *
     * <p>守护 {@code submit}：失败也建结果即失败。
     */
    @Test
    void submitValidationFailShouldOnlyWriteFailLog() {
        when(submitLogDbService.getByRequestId("req-1")).thenReturn(null);
        when(knowledgeBaseDbService.getActiveById(1L)).thenReturn(activeKb());
        when(fileService.validate("88"))
                .thenReturn(FileValidationResult.fail(FileValidationFailReason.FORMAT_NOT_ALLOWED));
        when(submitLogDbService.save(any(WbSubmitLog.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbSubmitLog.class).setId(30L);
            return true;
        });

        FileSubmitVO response = service.submit(1L, request());

        assertEquals(SubmitStatus.FAIL.name(), response.getSubmitLog().getStatus());
        assertEquals(FileValidationFailReason.FORMAT_NOT_ALLOWED.name(), response.getSubmitLog().getFailReason());
        assertNull(response.getPipelineTaskId());
        verify(kbFileResultDbService, never()).save(any(WbFileResult.class));
        verify(pipelineTaskDbService, never()).save(any(WbPipelineTask.class));
    }

    /**
     * 文件不存在：同样留一条 FAIL 日志。
     *
     * <p>守护 {@code submit}：异常吞掉不留痕即失败。
     */
    @Test
    void submitFileNotFoundShouldRecordFailLog() {
        when(submitLogDbService.getByRequestId("req-1")).thenReturn(null);
        when(knowledgeBaseDbService.getActiveById(1L)).thenReturn(activeKb());
        when(fileService.validate("88"))
                .thenReturn(FileValidationResult.fail(FileValidationFailReason.FILE_NOT_FOUND));
        when(submitLogDbService.save(any(WbSubmitLog.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbSubmitLog.class).setId(30L);
            return true;
        });

        FileSubmitVO response = service.submit(1L, request());

        assertEquals(SubmitStatus.FAIL.name(), response.getSubmitLog().getStatus());
        assertEquals(FileValidationFailReason.FILE_NOT_FOUND.name(), response.getSubmitLog().getFailReason());
        verify(kbFileResultDbService, never()).save(any(WbFileResult.class));
    }

    /**
     * 提交：停用库拒绝（40421）。
     *
     * <p>守护 {@code submit}：停用库可提交即失败。
     */
    @Test
    void submitToDisabledKbShouldThrow() {
        when(submitLogDbService.getByRequestId("req-1")).thenReturn(null);
        WbKnowledgeBase disabled = new WbKnowledgeBase();
        disabled.setId(1L);
        disabled.setStatus(KnowledgeBaseStatus.DISABLED.getCode());
        disabled.setUserId(ME);
        when(knowledgeBaseDbService.getActiveById(1L)).thenReturn(disabled);

        WorkbuddyException e = assertThrows(WorkbuddyException.class, () -> service.submit(1L, request()));
        assertEquals(ErrorCode.KB_NOT_ACTIVE, e.getErrorCode());
    }

    /**
     * 提交：别人的库按不存在处理。
     *
     * <p>守护 {@code submit}：越权可提交即失败。
     */
    @Test
    void submitToOthersKbShouldBehaveAsNotFound() {
        // 看不到的库也不能往里塞文档
        when(submitLogDbService.getByRequestId("req-1")).thenReturn(null);
        WbKnowledgeBase others = activeKb();
        others.setUserId(ME + 1);
        when(knowledgeBaseDbService.getActiveById(1L)).thenReturn(others);

        WorkbuddyException e = assertThrows(WorkbuddyException.class, () -> service.submit(1L, request()));

        assertEquals(ErrorCode.KB_NOT_FOUND, e.getErrorCode());
        verify(sourceFileDbService, never()).save(any(WbSourceFile.class));
    }

    /**
     * 提交：缺幂等键拒绝（REQUEST_ID_MISSING）。
     *
     * <p>守护 {@code submit}：缺键放行即失败。
     */
    @Test
    void submitMissingRequestIdShouldThrow() {
        FileSubmitDTO request = request();
        request.setRequestId("  ");

        WorkbuddyException e = assertThrows(WorkbuddyException.class, () -> service.submit(1L, request));
        assertEquals(ErrorCode.REQUEST_ID_MISSING, e.getErrorCode());
    }

    /**
     * 回放 FAIL 记录：失败日志没有文件结果，解析任务号保持 null（不是 0）。
     *
     * <p>守护 {@code findParseTaskId} 的 null 入参分支：返回 0 会让前端去轮询 0 号任务。
     */
    @Test
    void submitReplayOfFailLogShouldReturnNullTaskId() {
        WbSubmitLog failed = new WbSubmitLog();
        failed.setId(31L);
        failed.setKnowledgeBaseId(2L);
        failed.setRequestId("req-fail");
        failed.setStatus("FAIL");
        when(submitLogDbService.getByRequestId("req-fail")).thenReturn(failed);

        FileSubmitDTO request = new FileSubmitDTO();
        request.setRequestId("req-fail");
        request.setFileId("file-1");

        FileSubmitVO response = service.submit(2L, request);

        assertNull(response.getPipelineTaskId());
        verify(pipelineTaskDbService, never()).getByFileResultIdAndStage(any(), any());
    }
}
