package com.workbuddy.biz.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import com.workbuddy.common.domain.storage.StorageRef;
import com.workbuddy.common.dto.request.knowledge.SubmitLogQueryDTO;
import com.workbuddy.common.dto.response.input.FileResultVO;
import com.workbuddy.common.dto.response.input.SubmitLogVO;
import com.workbuddy.common.dto.response.task.StageStatusVO;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.filecenter.provider.StorageRouter;
import com.workbuddy.filecenter.storage.FileStorage;
import com.workbuddy.worker.input.FileValidatorPort;
import com.workbuddy.biz.service.support.assemble.InputVoAssemblerImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 提交记录与文件结果分页单测：行映射、五环节状态装配、存储口径与环节校验。
 */
class KnowledgeBaseFilePageTest extends KnowledgeBaseFileTestFixture {

    private WbSubmitLogDbService submitLogDbService;

    private WbFileResultDbService kbFileResultDbService;

    private WbSourceFileDbService sourceFileDbService;

    private WbPipelineTaskDbService pipelineTaskDbService;

    private FileStorage fileStorage;

    private StorageRouter storageRouter;

    private KnowledgeBaseFileServiceImpl service;

    /**
     * 每个用例重建分页查询服务；分页路径不做归属校验，登录态由提交类用例覆盖。
     */
    @BeforeEach
    void setUp() {
        submitLogDbService = mock(WbSubmitLogDbService.class);
        kbFileResultDbService = mock(WbFileResultDbService.class);
        sourceFileDbService = mock(WbSourceFileDbService.class);
        pipelineTaskDbService = mock(WbPipelineTaskDbService.class);
        fileStorage = mock(FileStorage.class);
        storageRouter = mock(StorageRouter.class);
        service = new KnowledgeBaseFileServiceImpl(submitLogDbService, kbFileResultDbService,
                sourceFileDbService, pipelineTaskDbService, mock(FileValidatorPort.class), fileStorage, storageRouter,
                TransactionTestSupport.template(), new InputVoAssemblerImpl(),
                new KnowledgeBaseAccessSupport(mock(WbKnowledgeBaseDbService.class)));
    }

    /**
     * 文件结果分页：五环节最新状态按管线顺序附着。
     *
     * <p>守护 {@code pageFileResults}：取旧任务或乱序即失败。
     */
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
        // 环节状态查询：本条结果没有任何任务（列表页只问五个环节）
        stubNoStageTasks(pipelineTaskDbService, List.of(10L));
    }

    /**
     * 文件结果分页：两个数据源的显示名分别下发。
     *
     * <p>守护 {@code pageFileResults}：同类型数据源无法区分即失败。
     */
    @Test
    void pageFileResultsShouldAttachSourceNames() {
        stubStorageNames("MinIO-A", "MinIO-B");

        FileResultVO record = service.pageFileResults(1L, fileResultQuery(null)).getRecords().getFirst();

        // 两个数据源实例同类型（minio）：显示名才是区分它们的字段
        assertEquals("MinIO-A", record.getStorageSourceName());
        assertEquals("MinIO-B", record.getCurrentStorageSourceName());
    }

    /**
     * 文件结果分页：数据源未注册时显示名留空。
     *
     * <p>守护 {@code pageFileResults}：空指针或错名即失败。
     */
    @Test
    void pageFileResultsShouldLeaveSourceNameEmptyWhenSourceUnregistered() {
        // 数据源已删：档案仍记着 ID，注册表给不出显示名
        stubStorageNames(null, null);

        FileResultVO record = service.pageFileResults(1L, fileResultQuery(null)).getRecords().getFirst();

        assertEquals(SOURCE_ID, record.getStorageSourceId());
        assertNull(record.getStorageSourceName());
        assertNull(record.getCurrentStorageSourceName());
    }

    /**
     * 文件结果分页：合法环节只做校验不额外过滤。
     *
     * <p>守护 {@code pageFileResults}：误过滤或多次查询即失败。
     */
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
        assertTrue(mockingDetails(kbFileResultDbService).getInvocations().stream()
                .anyMatch(inv -> "pageByKb".equals(inv.getMethod().getName())));
    }

    /**
     * 文件结果分页：未知环节按 40001 拒绝。
     *
     * <p>守护 {@code pageFileResults}：未知环节被放行即失败。
     */
    @Test
    void pageFileResultsShouldRejectUnknownStage() {
        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> service.pageFileResults(1L, fileResultQuery("INDEX")));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        verify(kbFileResultDbService, never()).pageByKb(anyLong(), anyLong(), any());
    }

    /**
     * 提交记录分页：按行转换成 VO，失败原因随行返回。
     *
     * <p>守护 {@code pageSubmitLogs} 的转换链路：直接返回实体或丢字段即失败。
     */
    @Test
    void pageSubmitLogsShouldConvertRows() {
        WbSubmitLog fail = new WbSubmitLog();
        fail.setId(30L);
        fail.setKnowledgeBaseId(1L);
        fail.setRequestId("req-1");
        fail.setFileId("88");
        fail.setFileName("坏文件.exe");
        fail.setStatus("FAIL");
        fail.setFailReason("FORMAT_NOT_ALLOWED");
        Page<WbSubmitLog> page = new Page<>(1, 10);
        page.setRecords(List.of(fail));
        when(submitLogDbService.pageByKb(1L, 10L, 1L, "FAIL", "坏文件")).thenReturn(page);
        SubmitLogQueryDTO query = new SubmitLogQueryDTO();
        query.setStatus("FAIL");
        query.setFileName("坏文件");

        List<SubmitLogVO> records = service.pageSubmitLogs(1L, query).getRecords();

        assertEquals(1, records.size());
        assertEquals(30L, records.getFirst().getId());
        assertEquals("req-1", records.getFirst().getRequestId());
        assertEquals("坏文件.exe", records.getFirst().getFileName());
        assertEquals("FAIL", records.getFirst().getStatus());
        assertEquals("FORMAT_NOT_ALLOWED", records.getFirst().getFailReason());
    }
}
