package com.workbuddy.biz.service.impl;

import com.workbuddy.biz.service.db.WbFileResultDbService;
import com.workbuddy.biz.service.db.WbKnowledgeBaseDbService;
import com.workbuddy.biz.service.db.WbPipelineTaskDbService;
import com.workbuddy.biz.service.db.WbSourceFileDbService;
import com.workbuddy.biz.service.db.WbSubmitLogDbService;
import com.workbuddy.biz.service.support.access.KnowledgeBaseAccessSupport;
import com.workbuddy.biz.testkit.TransactionTestSupport;
import com.workbuddy.common.domain.entity.WbFileResult;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.domain.entity.WbSourceFile;
import com.workbuddy.common.domain.entity.WbSubmitLog;
import com.workbuddy.common.dto.response.input.FileSubmitVO;
import com.workbuddy.common.enums.input.SubmitStatus;
import com.workbuddy.common.enums.task.PipelineStage;
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
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文件提交成功、幂等与并发冲突单测（文档输入链路的建档三写）。
 */
class KnowledgeBaseFileSubmitTest extends KnowledgeBaseFileTestFixture {

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
     * 提交成功：建档三写逐列落库，不登记任务。
     *
     * <p>守护 {@code submit}：漏写任一列或顺手建任务即失败。
     */
    @Test
    void submitSuccessShouldWriteThreeWithoutTask() {
        when(submitLogDbService.getByRequestId("req-1")).thenReturn(null);
        when(knowledgeBaseDbService.getActiveById(1L)).thenReturn(activeKb());
        when(fileService.validate("88")).thenReturn(passResult());
        when(fileStorage.metadata("88")).thenReturn(metadata());
        stubSuccessWrites(sourceFileDbService, kbFileResultDbService, submitLogDbService);

        FileSubmitVO response = service.submit(1L, request());

        assertNotNull(response.getSubmitLog());
        assertEquals(SubmitStatus.PASS.name(), response.getSubmitLog().getStatus());
        // 手动逐环节口径：提交只建档三写，不登记任务（解析由页面触发）
        assertNull(response.getPipelineTaskId());
        verify(pipelineTaskDbService, never()).save(any(WbPipelineTask.class));

        // 来源文件：校验结果与元数据逐列落库
        ArgumentCaptor<WbSourceFile> sourceCaptor = ArgumentCaptor.forClass(WbSourceFile.class);
        verify(sourceFileDbService).save(sourceCaptor.capture());
        WbSourceFile savedSource = sourceCaptor.getValue();
        assertEquals("88", savedSource.getFileId());
        assertEquals("sha256-abc", savedSource.getSha256());
        assertEquals("招标文件.pdf", savedSource.getFileName());
        assertEquals("application/pdf", savedSource.getMimeType());
        assertEquals(100L, savedSource.getFileSize());
        assertEquals(Long.valueOf(ME), savedSource.getUserId());
        assertNotNull(savedSource.getInputSnapshot());

        // 文件结果：知识库、来源文件、归属缺一不可
        ArgumentCaptor<WbFileResult> resultCaptor = ArgumentCaptor.forClass(WbFileResult.class);
        verify(kbFileResultDbService).save(resultCaptor.capture());
        WbFileResult savedResult = resultCaptor.getValue();
        assertEquals(1L, savedResult.getKnowledgeBaseId());
        assertEquals(1L, savedResult.getSourceFileId());
        assertEquals(Long.valueOf(ME), savedResult.getUserId());
        assertNotNull(savedResult.getOwner());

        // 提交记录：请求与校验结果逐列落库
        ArgumentCaptor<WbSubmitLog> logCaptor = ArgumentCaptor.forClass(WbSubmitLog.class);
        verify(submitLogDbService).save(logCaptor.capture());
        WbSubmitLog savedLog = logCaptor.getValue();
        assertEquals(1L, savedLog.getKnowledgeBaseId());
        assertEquals("req-1", savedLog.getRequestId());
        assertEquals("88", savedLog.getFileId());
        assertEquals("sha256-abc", savedLog.getSha256());
        assertEquals("招标文件.pdf", savedLog.getFileName());
        assertEquals(10L, savedLog.getFileResultId());
        assertEquals(SubmitStatus.PASS.name(), savedLog.getStatus());
        assertEquals(Long.valueOf(ME), savedLog.getUserId());

        // 文件校验与元数据是外部 IO，必须先于事务内的三写
        InOrder externalFirst = inOrder(fileService, fileStorage, sourceFileDbService);
        externalFirst.verify(fileService).validate("88");
        externalFirst.verify(fileStorage).metadata("88");
        externalFirst.verify(sourceFileDbService).save(any(WbSourceFile.class));
    }

    /**
     * 同知识库同内容重复提交：按 sha256 命中已有来源文件且本库已有文件结果时拒绝。
     *
     * <p>守护 {@link ErrorCode#FILE_DUPLICATE}（40423）：重复内容不能再次建档。
     */
    @Test
    void submitShouldRejectDuplicateContentInSameKb() {
        when(knowledgeBaseDbService.getActiveById(1L)).thenReturn(activeKb());
        when(fileService.validate("88")).thenReturn(passResult());
        WbSourceFile same = new WbSourceFile();
        same.setId(7L);
        same.setFileName("招标文件.pdf");
        when(sourceFileDbService.listBySha256("sha256-abc")).thenReturn(List.of(same));
        WbFileResult existingResult = new WbFileResult();
        existingResult.setSourceFileId(7L);
        when(kbFileResultDbService.listByKb(1L)).thenReturn(List.of(existingResult));

        WorkbuddyException error = assertThrows(WorkbuddyException.class,
                () -> service.submit(1L, request()));

        assertEquals(ErrorCode.FILE_DUPLICATE, error.getErrorCode());
        verify(sourceFileDbService, never()).save(any(WbSourceFile.class));
        verify(kbFileResultDbService, never()).save(any(WbFileResult.class));
    }

    /**
     * 来源文件已存在时直接复用，不再新建。
     *
     * <p>守护 {@code findOrCreateSourceFile} 的复用分支：新建会造成同一 fileId 两行档案。
     */
    @Test
    void submitShouldReuseExistingSourceFile() {
        when(submitLogDbService.getByRequestId("req-1")).thenReturn(null);
        when(knowledgeBaseDbService.getActiveById(1L)).thenReturn(activeKb());
        when(fileService.validate("88")).thenReturn(passResult());
        when(fileStorage.metadata("88")).thenReturn(metadata());
        WbSourceFile existing = new WbSourceFile();
        existing.setId(7L);
        existing.setFileId("88");
        when(sourceFileDbService.findByFileId("88")).thenReturn(existing);
        when(kbFileResultDbService.save(any(WbFileResult.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbFileResult.class).setId(10L);
            return true;
        });
        when(submitLogDbService.save(any(WbSubmitLog.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbSubmitLog.class).setId(30L);
            return true;
        });

        service.submit(1L, request());

        ArgumentCaptor<WbFileResult> captor = ArgumentCaptor.forClass(WbFileResult.class);
        verify(kbFileResultDbService).save(captor.capture());
        assertEquals(7L, captor.getValue().getSourceFileId());
        verify(sourceFileDbService, never()).save(any(WbSourceFile.class));
    }

    /**
     * 幂等：同 requestId 回放已有记录并回填解析任务。
     *
     * <p>守护 {@code submit}：重复建档即失败。
     */
    @Test
    void submitIdempotentReplayShouldReturnExisting() {
        WbSubmitLog existing = new WbSubmitLog();
        existing.setId(30L);
        existing.setRequestId("req-1");
        existing.setFileResultId(10L);
        existing.setStatus(SubmitStatus.PASS.name());
        when(submitLogDbService.getByRequestId("req-1")).thenReturn(existing);
        WbPipelineTask task = new WbPipelineTask();
        task.setId(20L);
        when(pipelineTaskDbService.getByFileResultIdAndStage(10L, PipelineStage.PARSE.name())).thenReturn(task);

        FileSubmitVO response = service.submit(1L, request());

        assertEquals(30L, response.getSubmitLog().getId());
        assertEquals(20L, response.getPipelineTaskId());
        verify(fileService, never()).validate(any());
        verify(knowledgeBaseDbService, never()).getActiveById(any());
    }

    /**
     * 并发：提交日志撞唯一键时回放对方记录。
     *
     * <p>守护 {@code submit}：冲突抛给用户即失败。
     */
    @Test
    void submitLogDuplicateKeyShouldReplayExisting() {
        when(knowledgeBaseDbService.getActiveById(1L)).thenReturn(activeKb());
        when(fileService.validate("88")).thenReturn(passResult());
        when(fileStorage.metadata("88")).thenReturn(metadata());
        when(sourceFileDbService.findByFileId("88")).thenReturn(null);
        when(sourceFileDbService.save(any(WbSourceFile.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbSourceFile.class).setId(1L);
            return true;
        });
        when(kbFileResultDbService.save(any(WbFileResult.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbFileResult.class).setId(10L);
            return true;
        });
        // 并发冲突：save 撞唯一键，重查拿到对方已提交记录
        when(submitLogDbService.save(any(WbSubmitLog.class))).thenThrow(new DuplicateKeyException("uk_request_id"));
        WbSubmitLog raced = new WbSubmitLog();
        raced.setId(30L);
        raced.setRequestId("req-1");
        raced.setFileResultId(10L);
        raced.setStatus(SubmitStatus.PASS.name());
        when(submitLogDbService.getByRequestId("req-1")).thenReturn(null, raced);
        WbPipelineTask task = new WbPipelineTask();
        task.setId(20L);
        when(pipelineTaskDbService.getByFileResultIdAndStage(10L, PipelineStage.PARSE.name())).thenReturn(task);

        FileSubmitVO response = service.submit(1L, request());

        assertEquals(30L, response.getSubmitLog().getId());
        assertEquals(20L, response.getPipelineTaskId());
    }

    /**
     * 并发：来源文件撞唯一键时复用已有行。
     *
     * <p>守护 {@code submit}：重复建档即失败。
     */
    @Test
    void sourceFileDuplicateKeyShouldReuseExistingRow() {
        when(submitLogDbService.getByRequestId("req-1")).thenReturn(null);
        when(knowledgeBaseDbService.getActiveById(1L)).thenReturn(activeKb());
        when(fileService.validate("88")).thenReturn(passResult());
        when(fileStorage.metadata("88")).thenReturn(metadata());
        // 同 fileId 并发建档：先查无、保存撞 uk_file_id、重查复用
        when(sourceFileDbService.save(any(WbSourceFile.class))).thenThrow(new DuplicateKeyException("uk_file_id"));
        WbSourceFile existing = new WbSourceFile();
        existing.setId(7L);
        existing.setFileId("88");
        when(sourceFileDbService.findByFileId("88")).thenReturn(null, existing);
        when(kbFileResultDbService.save(any(WbFileResult.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbFileResult.class).setId(10L);
            return true;
        });
        when(submitLogDbService.save(any(WbSubmitLog.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbSubmitLog.class).setId(30L);
            return true;
        });

        FileSubmitVO response = service.submit(1L, request());

        assertNull(response.getPipelineTaskId());
        ArgumentCaptor<WbFileResult> captor = ArgumentCaptor.forClass(WbFileResult.class);
        verify(kbFileResultDbService).save(captor.capture());
        assertEquals(7L, captor.getValue().getSourceFileId());
    }
}
