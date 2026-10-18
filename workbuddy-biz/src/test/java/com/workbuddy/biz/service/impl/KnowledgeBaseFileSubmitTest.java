package com.workbuddy.biz.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workbuddy.common.dto.request.knowledge.FileResultQueryDTO;
import com.workbuddy.biz.service.db.WbAuditLogDbService;
import com.workbuddy.biz.service.db.WbFileResultDbService;
import com.workbuddy.biz.service.db.WbIndexSetDbService;
import com.workbuddy.biz.service.db.WbIndexVersionDbService;
import com.workbuddy.biz.service.db.WbPipelineStrategyVersionDbService;
import com.workbuddy.biz.service.db.WbPipelineTaskDbService;
import com.workbuddy.biz.service.db.WbSourceFileDbService;
import com.workbuddy.biz.service.db.WbStrategyBindingDbService;
import com.workbuddy.biz.service.db.WbSubmitLogDbService;
import com.workbuddy.biz.service.db.WbKnowledgeBaseDbService;
import com.workbuddy.biz.service.support.InputVoAssembler;
import com.workbuddy.biz.service.support.TaskVoAssembler;
import com.workbuddy.common.domain.entity.WbFileResult;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.domain.entity.WbSourceFile;
import com.workbuddy.common.domain.entity.WbSubmitLog;
import com.workbuddy.common.domain.entity.WbKnowledgeBase;
import com.workbuddy.common.domain.input.FileMetadata;
import com.workbuddy.common.domain.input.FileValidationResult;
import com.workbuddy.common.domain.storage.StorageRef;
import com.workbuddy.common.dto.request.input.FileSubmitDTO;
import com.workbuddy.common.dto.response.input.FileResultVO;
import com.workbuddy.common.dto.response.input.FileSubmitVO;
import com.workbuddy.common.dto.response.task.StageStatusVO;
import com.workbuddy.common.enums.input.FileFormat;
import com.workbuddy.common.enums.input.FileValidationFailReason;
import com.workbuddy.common.enums.input.SubmitStatus;
import com.workbuddy.common.enums.knowledge.KnowledgeBaseStatus;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.enums.user.UserRole;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.security.KnowledgeUser;
import com.workbuddy.filecenter.provider.StorageRouter;
import com.workbuddy.filecenter.storage.FileStorage;
import com.workbuddy.worker.input.FileValidatorPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 提交链路单测（文档输入：提交建档、提交记录、文件结果）。
 *
 * @author cxxl
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeBaseFileSubmitTest {

    @Mock
    private WbKnowledgeBaseDbService knowledgeBaseDbService;
    @Mock
    private WbAuditLogDbService kbAuditLogDbService;
    @Mock
    private WbStrategyBindingDbService strategyBindingDbService;
    @Mock
    private WbPipelineStrategyVersionDbService strategyVersionDbService;
    @Mock
    private WbFileResultDbService kbFileResultDbService;
    @Mock
    private WbIndexSetDbService indexSetDbService;
    @Mock
    private WbIndexVersionDbService indexVersionDbService;
    @Mock
    private WbSourceFileDbService sourceFileDbService;
    @Mock
    private WbSubmitLogDbService submitLogDbService;
    @Mock
    private WbPipelineTaskDbService pipelineTaskDbService;
    @Mock
    private FileValidatorPort fileValidator;
    @Mock
    private FileStorage fileStorage;
    @Mock
    private StorageRouter storageRouter;

    private KnowledgeBaseServiceImpl service;

    /** 当前登录用户：夹具知识库默认归它，正向用例才能过归属校验 */
    private static final long ME = 1001L;

    /** 档案所在的数据源 */
    private static final Long SOURCE_ID = 1L;

    /** 当前启用的数据源 */
    private static final Long CURRENT_SOURCE_ID = 2L;

    @BeforeEach
    void setUp() {
        // 存储口径：当前启用的数据源取运行时生效值，列表页的所属数据源按档案批量取（无档案时留空）；
        // 提交类用例不读存储口径，用宽松桩避免未使用告警
        lenient().when(storageRouter.writeType()).thenReturn(StorageType.MINIO);
        lenient().when(storageRouter.currentName()).thenReturn(null);
        lenient().when(fileStorage.refsOf(any())).thenReturn(Map.of());
        // 组装器为纯映射无状态类，用真实实例（mock 会让 VO 组装返回 null，无法验证响应内容）
        service = new KnowledgeBaseServiceImpl(knowledgeBaseDbService, kbAuditLogDbService,
                strategyBindingDbService, strategyVersionDbService, kbFileResultDbService,
                indexSetDbService, indexVersionDbService, sourceFileDbService, submitLogDbService,
                pipelineTaskDbService, fileValidator, fileStorage, storageRouter,
                new InputVoAssembler(), new TaskVoAssembler());
        // 提交链路会做知识库归属校验，用例必须有登录上下文
        KnowledgeUser user = new KnowledgeUser();
        user.setId(ME);
        user.setRole(UserRole.USER);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private FileSubmitDTO request() {
        FileSubmitDTO request = new FileSubmitDTO();
        request.setFileId("88");
        request.setRequestId("req-1");
        return request;
    }

    private WbKnowledgeBase activeKb() {
        WbKnowledgeBase kb = new WbKnowledgeBase();
        kb.setId(1L);
        kb.setStatus(KnowledgeBaseStatus.ACTIVE.getCode());
        kb.setUserId(ME);
        return kb;
    }

    private FileValidationResult passResult() {
        return FileValidationResult.pass(FileFormat.PDF, "application/pdf", 100L, "sha256-abc");
    }

    private FileMetadata metadata() {
        FileMetadata metadata = new FileMetadata();
        metadata.setFileName("招标文件.pdf");
        metadata.setFileSize(100L);
        return metadata;
    }

    private void stubSuccessWrites() {
        when(sourceFileDbService.findByFileId("88")).thenReturn(null);
        when(sourceFileDbService.save(any(WbSourceFile.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbSourceFile.class).setId(1L);
            return true;
        });
        when(kbFileResultDbService.save(any(WbFileResult.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbFileResult.class).setId(10L);
            return true;
        });
        when(submitLogDbService.save(any(WbSubmitLog.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbSubmitLog.class).setId(30L);
            return true;
        });
    }

    @Test
    void submitSuccessShouldWriteThreeWithoutTask() {
        when(submitLogDbService.getByRequestId("req-1")).thenReturn(null);
        when(knowledgeBaseDbService.getActiveById(1L)).thenReturn(activeKb());
        when(fileValidator.validate("88")).thenReturn(passResult());
        when(fileStorage.metadata("88")).thenReturn(metadata());
        stubSuccessWrites();

        FileSubmitVO response = service.submit(1L, request());

        assertNotNull(response.getSubmitLog());
        assertEquals(SubmitStatus.PASS.name(), response.getSubmitLog().getStatus());
        // 手动逐环节口径：提交只建档三写，不登记任务（解析由页面触发）
        assertNull(response.getPipelineTaskId());
        verify(kbFileResultDbService).save(any(WbFileResult.class));
        verify(pipelineTaskDbService, never()).save(any(WbPipelineTask.class));
    }

    @Test
    void submitValidationFailShouldOnlyWriteFailLog() {
        when(submitLogDbService.getByRequestId("req-1")).thenReturn(null);
        when(knowledgeBaseDbService.getActiveById(1L)).thenReturn(activeKb());
        when(fileValidator.validate("88"))
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

    @Test
    void submitFileNotFoundShouldRecordFailLog() {
        when(submitLogDbService.getByRequestId("req-1")).thenReturn(null);
        when(knowledgeBaseDbService.getActiveById(1L)).thenReturn(activeKb());
        when(fileValidator.validate("88"))
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
        verify(fileValidator, never()).validate(any());
        verify(knowledgeBaseDbService, never()).getActiveById(any());
    }

    @Test
    void submitLogDuplicateKeyShouldReplayExisting() {
        when(knowledgeBaseDbService.getActiveById(1L)).thenReturn(activeKb());
        when(fileValidator.validate("88")).thenReturn(passResult());
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

    @Test
    void sourceFileDuplicateKeyShouldReuseExistingRow() {
        when(submitLogDbService.getByRequestId("req-1")).thenReturn(null);
        when(knowledgeBaseDbService.getActiveById(1L)).thenReturn(activeKb());
        when(fileValidator.validate("88")).thenReturn(passResult());
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

    @Test
    void submitToDisabledKbShouldThrow() {
        when(submitLogDbService.getByRequestId("req-1")).thenReturn(null);
        WbKnowledgeBase disabled = new WbKnowledgeBase();
        disabled.setId(1L);
        disabled.setStatus(KnowledgeBaseStatus.DISABLED.getCode());
        disabled.setUserId(ME);
        when(knowledgeBaseDbService.getActiveById(1L)).thenReturn(disabled);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.submit(1L, request()));
        assertEquals(ErrorCode.KB_NOT_ACTIVE, e.getErrorCode());
    }

    @Test
    void submitToOthersKbShouldBehaveAsNotFound() {
        // 看不到的库也不能往里塞文档
        when(submitLogDbService.getByRequestId("req-1")).thenReturn(null);
        WbKnowledgeBase others = activeKb();
        others.setUserId(ME + 1);
        when(knowledgeBaseDbService.getActiveById(1L)).thenReturn(others);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.submit(1L, request()));

        assertEquals(ErrorCode.KB_NOT_FOUND, e.getErrorCode());
        verify(sourceFileDbService, never()).save(any(WbSourceFile.class));
    }

    @Test
    void submitMissingRequestIdShouldThrow() {
        FileSubmitDTO request = request();
        request.setRequestId("  ");

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.submit(1L, request));
        assertEquals(ErrorCode.REQUEST_ID_MISSING, e.getErrorCode());
    }

    @Test
    void pageFileResultsShouldAttachLatestStageStatuses() {
        WbFileResult fr10 = new WbFileResult();
        fr10.setId(10L);
        fr10.setSourceFileId(1L);
        WbFileResult fr11 = new WbFileResult();
        fr11.setId(11L);
        fr11.setSourceFileId(1L);
        Page<WbFileResult> page = new Page<>(1, 10);
        page.setRecords(List.of(fr10, fr11));
        when(kbFileResultDbService.pageByKb(1L, 10L, 1L)).thenReturn(page);
        WbSourceFile source = new WbSourceFile();
        source.setId(1L);
        source.setFileId("88");
        source.setFileName("招标文件.pdf");
        when(sourceFileDbService.listByIds(List.of(1L))).thenReturn(List.of(source));
        // 存储口径：所属数据源按档案批量取，当前启用的数据源取运行时生效值（不一致时由触发入口拒绝）
        when(fileStorage.refsOf(List.of("88")))
                .thenReturn(Map.of("88", new StorageRef(StorageType.MINIO.getCode(), SOURCE_ID)));
        when(storageRouter.currentSourceId()).thenReturn(CURRENT_SOURCE_ID);
        when(storageRouter.writeType()).thenReturn(StorageType.LOCAL);

        // 模拟 DB 倒序契约：同一 fileResult 两条任务时列表第一条即最新
        WbPipelineTask parseOld = new WbPipelineTask();
        parseOld.setId(2L);
        parseOld.setFileResultId(10L);
        parseOld.setStage(PipelineStage.PARSE.name());
        parseOld.setStatus(PipelineTaskStatus.FAILED.name());
        WbPipelineTask parseNew = new WbPipelineTask();
        parseNew.setId(21L);
        parseNew.setFileResultId(10L);
        parseNew.setStage(PipelineStage.PARSE.name());
        parseNew.setStatus(PipelineTaskStatus.SUCCESS.name());
        WbPipelineTask fr11Parse = new WbPipelineTask();
        fr11Parse.setId(22L);
        fr11Parse.setFileResultId(11L);
        fr11Parse.setStage(PipelineStage.PARSE.name());
        fr11Parse.setStatus(PipelineTaskStatus.RUNNING.name());
        when(pipelineTaskDbService.listByFileResultIdsAndStage(List.of(10L, 11L), PipelineStage.PARSE.name()))
                .thenReturn(List.of(parseNew, parseOld, fr11Parse));
        WbPipelineTask structure = new WbPipelineTask();
        structure.setId(51L);
        structure.setFileResultId(10L);
        structure.setStage(PipelineStage.STRUCTURE.name());
        structure.setStatus(PipelineTaskStatus.SUCCESS.name());
        when(pipelineTaskDbService.listByFileResultIdsAndStage(List.of(10L, 11L), PipelineStage.STRUCTURE.name()))
                .thenReturn(List.of(structure));
        WbPipelineTask preprocess = new WbPipelineTask();
        preprocess.setId(61L);
        preprocess.setFileResultId(10L);
        preprocess.setStage(PipelineStage.PREPROCESS.name());
        preprocess.setStatus(PipelineTaskStatus.SUCCESS.name());
        when(pipelineTaskDbService.listByFileResultIdsAndStage(List.of(10L, 11L), PipelineStage.PREPROCESS.name()))
                .thenReturn(List.of(preprocess));
        WbPipelineTask chunk = new WbPipelineTask();
        chunk.setId(71L);
        chunk.setFileResultId(10L);
        chunk.setStage(PipelineStage.CHUNK.name());
        chunk.setStatus(PipelineTaskStatus.SUCCESS.name());
        when(pipelineTaskDbService.listByFileResultIdsAndStage(List.of(10L, 11L), PipelineStage.CHUNK.name()))
                .thenReturn(List.of(chunk));
        WbPipelineTask embed = new WbPipelineTask();
        embed.setId(81L);
        embed.setFileResultId(10L);
        embed.setStage(PipelineStage.EMBED.name());
        embed.setStatus(PipelineTaskStatus.SUCCESS.name());
        when(pipelineTaskDbService.listByFileResultIdsAndStage(List.of(10L, 11L), PipelineStage.EMBED.name()))
                .thenReturn(List.of(embed));

        IPage<FileResultVO> result = service.pageFileResults(1L, fileResultQuery(null));

        List<FileResultVO> records = result.getRecords();
        assertEquals(2, records.size());
        // fr10：PARSE 取最新一条 + STRUCTURE + PREPROCESS + CHUNK + EMBED，按管线顺序排列（五环节齐）
        List<StageStatusVO> fr10Statuses = records.getFirst().getStageStatuses();
        assertNotNull(fr10Statuses);
        assertEquals(5, fr10Statuses.size());
        assertEquals(PipelineStage.PARSE.name(), fr10Statuses.getFirst().getStage());
        assertEquals(21L, fr10Statuses.get(0).getTaskId());
        assertEquals(PipelineTaskStatus.SUCCESS.name(), fr10Statuses.get(0).getStatus());
        assertEquals(PipelineStage.STRUCTURE.name(), fr10Statuses.get(1).getStage());
        assertEquals(51L, fr10Statuses.get(1).getTaskId());
        assertEquals(PipelineStage.PREPROCESS.name(), fr10Statuses.get(2).getStage());
        assertEquals(61L, fr10Statuses.get(2).getTaskId());
        assertEquals(PipelineStage.CHUNK.name(), fr10Statuses.get(3).getStage());
        assertEquals(71L, fr10Statuses.get(3).getTaskId());
        assertEquals(PipelineStage.EMBED.name(), fr10Statuses.get(4).getStage());
        assertEquals(81L, fr10Statuses.get(4).getTaskId());
        // fr11：仅 PARSE 一条
        List<StageStatusVO> fr11Statuses = records.get(1).getStageStatuses();
        assertNotNull(fr11Statuses);
        assertEquals(1, fr11Statuses.size());
        assertEquals(22L, fr11Statuses.getFirst().getTaskId());
        // 存储口径随行下发：所属数据源取档案、当前启用的数据源取运行时生效值
        assertEquals(StorageType.MINIO.getCode(), records.getFirst().getStorageType());
        assertEquals(SOURCE_ID, records.getFirst().getStorageSourceId());
        assertEquals(CURRENT_SOURCE_ID, records.getFirst().getCurrentStorageSourceId());
        assertEquals(StorageType.LOCAL.getCode(), records.getFirst().getCurrentStorageType());
    }

    /**
     * 单条文件结果的存储口径桩：档案记着数据源 1，当前启用数据源 2。
     *
     * @param storageSourceName 档案数据源的显示名（数据源未注册时传 null）
     * @param currentSourceName 当前启用数据源的显示名（未启用或未注册时传 null）
     */
    private void stubStorageNames(String storageSourceName, String currentSourceName) {
        WbFileResult fileResult = new WbFileResult();
        fileResult.setId(10L);
        fileResult.setSourceFileId(1L);
        Page<WbFileResult> page = new Page<>(1, 10);
        page.setRecords(List.of(fileResult));
        when(kbFileResultDbService.pageByKb(1L, 10L, 1L)).thenReturn(page);
        WbSourceFile source = new WbSourceFile();
        source.setId(1L);
        source.setFileId("88");
        when(sourceFileDbService.listByIds(List.of(1L))).thenReturn(List.of(source));
        when(fileStorage.refsOf(List.of("88")))
                .thenReturn(Map.of("88", new StorageRef(StorageType.MINIO.getCode(), SOURCE_ID)));
        when(storageRouter.nameOf(SOURCE_ID)).thenReturn(storageSourceName);
        when(storageRouter.currentSourceId()).thenReturn(CURRENT_SOURCE_ID);
        when(storageRouter.currentName()).thenReturn(currentSourceName);
        // 环节状态查询：本条结果没有任何任务（列表页只问五个环节，多出来的桩会被判为无用）
        for (String statusStage : List.of(PipelineStage.PARSE.name(), PipelineStage.STRUCTURE.name(),
                PipelineStage.PREPROCESS.name(), PipelineStage.CHUNK.name(), PipelineStage.EMBED.name())) {
            when(pipelineTaskDbService.listByFileResultIdsAndStage(List.of(10L), statusStage))
                    .thenReturn(List.of());
        }
    }

    @Test
    void pageFileResultsShouldAttachSourceNames() {
        stubStorageNames("MinIO-A", "MinIO-B");

        FileResultVO record = service.pageFileResults(1L, fileResultQuery(null)).getRecords().getFirst();

        // 两个数据源实例同类型（minio）：显示名才是区分它们的字段
        assertEquals("MinIO-A", record.getStorageSourceName());
        assertEquals("MinIO-B", record.getCurrentStorageSourceName());
    }

    @Test
    void pageFileResultsShouldLeaveSourceNameEmptyWhenSourceUnregistered() {
        // 数据源已删：档案仍记着 ID，注册表给不出显示名
        stubStorageNames(null, null);

        FileResultVO record = service.pageFileResults(1L, fileResultQuery(null)).getRecords().getFirst();

        assertEquals(SOURCE_ID, record.getStorageSourceId());
        assertNull(record.getStorageSourceName());
        assertNull(record.getCurrentStorageSourceName());
    }

    @Test
    void pageFileResultsShouldValidateStageOnly() {
        when(kbFileResultDbService.pageByKb(1L, 10L, 1L))
                .thenReturn(new Page<>(1, 10));

        // 合法 stage（PARSE/STRUCTURE/空）只做合法性校验；按上游产物过滤在解析环节落地后启用
        service.pageFileResults(1L, fileResultQuery("PARSE"));
        service.pageFileResults(1L, fileResultQuery("STRUCTURE"));
        service.pageFileResults(1L, fileResultQuery(null));
        service.pageFileResults(1L, fileResultQuery(""));

        verify(kbFileResultDbService, times(4)).pageByKb(1L, 10L, 1L);
    }

    @Test
    void pageFileResultsShouldRejectUnknownStage() {
        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> service.pageFileResults(1L, fileResultQuery("INDEX")));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        verify(kbFileResultDbService, never()).pageByKb(anyLong(), anyLong(), any());
    }

    /** 文件结果分页查询条件（环节可空） */
    private FileResultQueryDTO fileResultQuery(String stage) {
        FileResultQueryDTO query = new FileResultQueryDTO();
        query.setStage(stage);
        return query;
    }
}
