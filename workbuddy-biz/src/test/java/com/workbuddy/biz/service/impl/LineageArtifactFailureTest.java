package com.workbuddy.biz.service.impl;

import com.workbuddy.biz.service.db.WbChunkSetDbService;
import com.workbuddy.biz.service.db.WbEmbeddingSetDbService;
import com.workbuddy.biz.service.db.WbFileResultDbService;
import com.workbuddy.biz.service.db.WbKnowledgeBaseDbService;
import com.workbuddy.biz.service.db.WbPipelineProductDbService;
import com.workbuddy.biz.service.db.WbPipelineStepLogDbService;
import com.workbuddy.biz.service.db.WbPipelineTaskDbService;
import com.workbuddy.biz.service.support.access.FileResultAccessGuard;
import com.workbuddy.biz.testkit.SecurityTestSupport;
import com.workbuddy.common.domain.entity.WbKnowledgeBase;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.dto.response.lineage.LineageNodeVO;
import com.workbuddy.common.dto.response.lineage.LineageVO;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.filecenter.storage.FileStorage;
import com.workbuddy.worker.chunking.ChunkProperties;
import com.workbuddy.worker.chunking.strategy.ChunkStrategyParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static com.workbuddy.biz.service.impl.LineageQueryTestFixtures.FILE_RESULT_ID;
import static com.workbuddy.biz.service.impl.LineageQueryTestFixtures.KB_ID;
import static com.workbuddy.biz.service.impl.LineageQueryTestFixtures.SOURCE_ID;
import static com.workbuddy.biz.service.impl.LineageQueryTestFixtures.fileResultOfKb10;
import static com.workbuddy.biz.service.impl.LineageQueryTestFixtures.knowledgeBaseOf;
import static com.workbuddy.biz.service.impl.LineageQueryTestFixtures.product;
import static com.workbuddy.biz.service.impl.LineageQueryTestFixtures.task;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 执行树产物读取失败单测：非存储类失败降级为无统计，存储类失败向上抛出。
 *
 * <p>守护 {@code lineage} 的错误降级口径：产物不可读时不得下发空统计或"无异常"摘要；
 * 40454 不能被吞成空节点。
 */
@ExtendWith(MockitoExtension.class)
class LineageArtifactFailureTest {

    @Mock
    private WbFileResultDbService fileResultDbService;
    @Mock
    private WbPipelineTaskDbService pipelineTaskDbService;
    @Mock
    private WbPipelineProductDbService pipelineProductDbService;
    @Mock
    private WbChunkSetDbService chunkSetDbService;
    @Mock
    private WbEmbeddingSetDbService embeddingSetDbService;
    @Mock
    private WbKnowledgeBaseDbService knowledgeBaseDbService;
    @Mock
    private FileStorage fileStorage;

    private LineageQueryServiceImpl service;

    @BeforeEach
    void setUp() {
        SecurityTestSupport.loginViewer();
        WbKnowledgeBase kb = knowledgeBaseOf(KB_ID);
        kb.setUserId(SecurityTestSupport.VIEWER_ID);
        lenient().when(knowledgeBaseDbService.getActiveById(KB_ID)).thenReturn(kb);
        FileResultAccessGuard accessGuard = new FileResultAccessGuard(fileResultDbService, knowledgeBaseDbService);
        service = new LineageQueryServiceImpl(pipelineTaskDbService, pipelineProductDbService,
                mock(WbPipelineStepLogDbService.class), chunkSetDbService, embeddingSetDbService, fileStorage,
                accessGuard, new ChunkStrategyParser(new ChunkProperties()));
    }

    /** 单任务文件与空集合行夹具 */
    private void stubFile(WbPipelineTask parseTask) {
        when(fileResultDbService.getById(FILE_RESULT_ID)).thenReturn(fileResultOfKb10());
        when(pipelineTaskDbService.listByFileResultId(FILE_RESULT_ID)).thenReturn(List.of(parseTask));
        when(pipelineProductDbService.listByFileResultId(FILE_RESULT_ID)).thenReturn(List.of(
                product(10L, PipelineStage.PARSE.name(), "art-p",
                        "{\"parserName\":\"pdfbox\",\"parserVersion\":\"3.0.4\"}")));
        when(chunkSetDbService.listByFileResultId(FILE_RESULT_ID)).thenReturn(List.of());
        when(embeddingSetDbService.listByFileResultId(FILE_RESULT_ID)).thenReturn(List.of());
    }

    /** 解析产物对象位置（与产物行同键） */
    private ObjectRef parseRef() {
        return ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(), "artifacts", "art-p");
    }

    /**
     * 产物不可读：统计与摘要都不下发 —— 统计没到手时不得断言"无异常"。
     *
     * <p>成功与部分成功两种终态都覆盖：两者的摘要判据同源（统计里的问题单元）。
     */
    @ParameterizedTest
    @EnumSource(value = PipelineTaskStatus.class, names = { "SUCCESS", "PARTIAL_SUCCESS" })
    void unreadableArtifactShouldLeaveParseStatsAndSummaryEmpty(PipelineTaskStatus status) {
        WbPipelineTask parseTask = task(20L, PipelineStage.PARSE.name(), null, 10L, null);
        parseTask.setStatus(status.name());
        parseTask.setStartedAt(LocalDateTime.of(2024, 5, 1, 10, 0, 0));
        parseTask.setFinishedAt(LocalDateTime.of(2024, 5, 1, 10, 0, 8));
        stubFile(parseTask);
        when(fileStorage.getObject(parseRef()))
                .thenThrow(new KnowledgeException(ErrorCode.FILE_NOT_FOUND, "对象读取失败"));

        LineageVO vo = service.lineage(FILE_RESULT_ID);

        LineageNodeVO node = vo.getNodes().get(0);
        assertAll(
                () -> assertNull(node.getParseStats(), status + ": 产物不可读时不下发统计"),
                () -> assertNull(node.getParseSummary(), status + ": 产物不可读时不下发摘要"));
    }

    /**
     * 存储类读取失败（40454 对象在所属存储中不存在）：不吞掉，向上抛给统一异常处理 ——
     * 与"其它读取失败留空"是同一条读路径上的两种口径，不能混为一谈。
     */
    @Test
    void missingStorageObjectShouldPropagate40454() {
        WbPipelineTask parseTask = task(20L, PipelineStage.PARSE.name(), null, 10L, null);
        stubFile(parseTask);
        when(fileStorage.getObject(parseRef()))
                .thenThrow(new KnowledgeException(ErrorCode.STORAGE_OBJECT_MISSING, "对象已不存在"));

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.lineage(FILE_RESULT_ID));

        assertEquals(ErrorCode.STORAGE_OBJECT_MISSING, e.getErrorCode());
    }
}
