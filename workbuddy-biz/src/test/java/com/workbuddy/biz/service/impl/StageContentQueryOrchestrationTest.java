package com.workbuddy.biz.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workbuddy.biz.service.StageContentQueryService;
import com.workbuddy.biz.service.db.WbChunkDbService;
import com.workbuddy.biz.service.db.WbChunkSetDbService;
import com.workbuddy.biz.service.db.WbEmbeddingRecordDbService;
import com.workbuddy.biz.service.db.WbEmbeddingSetDbService;
import com.workbuddy.biz.service.db.WbFileResultDbService;
import com.workbuddy.biz.service.db.WbKnowledgeBaseDbService;
import com.workbuddy.biz.service.db.WbPipelineProductDbService;
import com.workbuddy.biz.service.db.WbPipelineStepLogDbService;
import com.workbuddy.biz.service.db.WbPipelineTaskDbService;
import com.workbuddy.biz.service.support.access.FileResultAccessGuard;
import com.workbuddy.biz.service.support.common.TaskDetailSupport;
import com.workbuddy.biz.testkit.SecurityTestSupport;
import com.workbuddy.common.domain.entity.WbChunk;
import com.workbuddy.common.domain.entity.WbChunkSet;
import com.workbuddy.common.domain.entity.WbKnowledgeBase;
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.dto.response.stagecontent.StageContentVO;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.WorkbuddyException;
import com.workbuddy.filecenter.storage.FileStorage;
import com.workbuddy.biz.service.support.stagecontent.ChunkStageContentSupport;
import com.workbuddy.biz.service.support.stagecontent.EmbedStageContentSupport;
import com.workbuddy.biz.service.support.stagecontent.ParseStageContentSupport;
import com.workbuddy.biz.service.support.stagecontent.StructureStageContentSupport;
import com.workbuddy.common.convert.KnowledgeConverterImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static com.workbuddy.biz.service.impl.StageContentQueryTestFixtures.FILE_RESULT_ID;
import static com.workbuddy.biz.service.impl.StageContentQueryTestFixtures.PRODUCT_ID;
import static com.workbuddy.biz.service.impl.StageContentQueryTestFixtures.TASK_ID;
import static com.workbuddy.biz.service.impl.StageContentQueryTestFixtures.fileResultOfKb10;
import static com.workbuddy.biz.service.impl.StageContentQueryTestFixtures.knowledgeBaseOf;
import static com.workbuddy.biz.service.impl.StageContentQueryTestFixtures.latestTask;
import static com.workbuddy.biz.service.impl.StageContentQueryTestFixtures.productRow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 产物内容查询编排单测：环节/任务/产物解析、空骨架、错误降级、归属校验与环节标签。
 *
 * <p>守护 {@code stageContent} 的入口语义：白名单、任务归属、产物精确读取与读取失败降级；
 * 元素映射见 {@link StageContentStageMappingTest}，分页与过滤见其余拆分用例。
 */
@ExtendWith(MockitoExtension.class)
class StageContentQueryOrchestrationTest {

    @Mock
    private WbFileResultDbService fileResultDbService;
    @Mock
    private WbKnowledgeBaseDbService knowledgeBaseDbService;
    @Mock
    private WbPipelineTaskDbService pipelineTaskDbService;
    @Mock
    private WbPipelineStepLogDbService stepLogDbService;
    @Mock
    private WbPipelineProductDbService pipelineProductDbService;
    @Mock
    private WbChunkSetDbService chunkSetDbService;
    @Mock
    private WbChunkDbService chunkDbService;
    @Mock
    private FileStorage fileStorage;

    private StageContentQueryServiceImpl service;

    @BeforeEach
    void setUp() {
        SecurityTestSupport.loginViewer();
        WbKnowledgeBase kb = knowledgeBaseOf(10L);
        kb.setUserId(SecurityTestSupport.VIEWER_ID);
        lenient().when(knowledgeBaseDbService.getActiveById(10L)).thenReturn(kb);
        FileResultAccessGuard accessGuard = new FileResultAccessGuard(fileResultDbService, knowledgeBaseDbService);
        service = new StageContentQueryServiceImpl(pipelineProductDbService, chunkSetDbService, chunkDbService,
                mock(WbEmbeddingSetDbService.class), mock(WbEmbeddingRecordDbService.class), fileStorage,
                new TaskDetailSupport(pipelineTaskDbService, stepLogDbService, pipelineProductDbService),
                new StructureStageContentSupport(new KnowledgeConverterImpl())
                        , new ParseStageContentSupport(new KnowledgeConverterImpl())
                        , new EmbedStageContentSupport(new KnowledgeConverterImpl())
                        , new ChunkStageContentSupport(new KnowledgeConverterImpl()), accessGuard);
    }

    /**
     * 守护 {@code illegalStageShouldReject40001}：该场景行为改动即失败。
     */
    @Test
    void illegalStageShouldReject40001() {
        when(fileResultDbService.getById(FILE_RESULT_ID)).thenReturn(fileResultOfKb10());

        WorkbuddyException e = assertThrows(WorkbuddyException.class,
                () -> service.stageContent(FILE_RESULT_ID, "BUILD_INDEX", null, null, null, null));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
    }

    /**
     * 守护 {@code missingFileResultShouldReject40432}：该场景行为改动即失败。
     */
    @Test
    void missingFileResultShouldReject40432() {
        when(fileResultDbService.getById(FILE_RESULT_ID)).thenReturn(null);

        WorkbuddyException e = assertThrows(WorkbuddyException.class,
                () -> service.stageContent(FILE_RESULT_ID, "CHUNK", null, null, null, null));
        assertEquals(ErrorCode.FILE_RESULT_NOT_FOUND, e.getErrorCode());
    }

    /**
     * 他人文件结果按 40401 拒绝（越权与不存在同码）。
     *
     * <p>守护 {@code otherUserFileResultShouldReject40401}：归属校验被绕过即失败。
     */
    @Test
    void otherUserFileResultShouldReject40401() {
        when(fileResultDbService.getById(FILE_RESULT_ID)).thenReturn(fileResultOfKb10());
        // 归属看的是知识库归属：换一个登录用户，越权与"不存在"同样返回 40401
        SecurityTestSupport.loginOtherUser();

        WorkbuddyException e = assertThrows(WorkbuddyException.class,
                () -> service.stageContent(FILE_RESULT_ID, "PARSE", null, null, null, null));
        assertEquals(ErrorCode.KB_NOT_FOUND, e.getErrorCode());
    }

    /**
     * 守护 {@code taskIdOfWrongStageShouldReject40001}：该场景行为改动即失败。
     */
    @Test
    void taskIdOfWrongStageShouldReject40001() {
        when(fileResultDbService.getById(FILE_RESULT_ID)).thenReturn(fileResultOfKb10());
        WbPipelineTask preprocessTask = new WbPipelineTask();
        preprocessTask.setId(TASK_ID);
        preprocessTask.setFileResultId(FILE_RESULT_ID);
        preprocessTask.setStage(PipelineStage.PREPROCESS.name());
        when(pipelineTaskDbService.getById(TASK_ID)).thenReturn(preprocessTask);

        WorkbuddyException e = assertThrows(WorkbuddyException.class,
                () -> service.stageContent(FILE_RESULT_ID, "CHUNK", TASK_ID, null, null, null));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
    }

    /**
     * 守护 {@code stageContentWithoutTaskShouldReturnLatestFalse}：该场景行为改动即失败。
     */
    @Test
    void stageContentWithoutTaskShouldReturnLatestFalse() {
        when(fileResultDbService.getById(FILE_RESULT_ID)).thenReturn(fileResultOfKb10());
        when(pipelineTaskDbService.getByFileResultIdAndStage(FILE_RESULT_ID, PipelineStage.CHUNK.name()))
                .thenReturn(null);

        StageContentVO vo = service.stageContent(FILE_RESULT_ID, "CHUNK", null, null, null, null);

        assertFalse(vo.getLatest());
        assertTrue(vo.getItems().isEmpty());
        assertNull(vo.getTaskId());
    }

    /**
     * 无任务时返回空壳：外壳字段齐全、条目为空、total 0、未截断。
     *
     * <p>守护 {@code stageContent} 的空任务兜底：外壳字段漏搬会让页面拿不到分页口径。
     */
    @Test
    void stageContentWithoutTaskShouldReturnEmptyScaffold() {
        when(fileResultDbService.getById(FILE_RESULT_ID)).thenReturn(fileResultOfKb10());
        when(pipelineTaskDbService.getByFileResultIdAndStage(FILE_RESULT_ID, PipelineStage.PARSE.name()))
                .thenReturn(null);

        StageContentVO vo = service.stageContent(FILE_RESULT_ID, "PARSE", null, 0L, 0, 0);

        assertEquals(FILE_RESULT_ID, vo.getFileResultId());
        assertEquals("PARSE", vo.getStage());
        assertFalse(vo.getLatest());
        assertNull(vo.getTaskId());
        // docPage 非正、page 非正、limit 非正都按缺省口径归一
        assertNull(vo.getDocPage());
        assertEquals(1, vo.getCurrent());
        assertEquals(StageContentQueryService.DEFAULT_SIZE, vo.getSize());
        assertEquals(0, vo.getTotal());
        assertTrue(vo.getItems().isEmpty());
        assertFalse(vo.getTruncated());
    }

    /**
     * 产物缺失时 latest 为 false，且不读对象存储。
     *
     * <p>守护 {@code stageContent} 的产物兜底：无产物仍标 latest 会让页面去对齐空内容。
     */
    @Test
    void stageContentWithoutProductShouldNotReadArtifact() {
        when(fileResultDbService.getById(FILE_RESULT_ID)).thenReturn(fileResultOfKb10());
        when(pipelineTaskDbService.getByFileResultIdAndStage(FILE_RESULT_ID, PipelineStage.STRUCTURE.name()))
                .thenReturn(latestTask(PipelineStage.STRUCTURE.name()));
        when(pipelineProductDbService.getById(PRODUCT_ID)).thenReturn(null);

        StageContentVO vo = service.stageContent(FILE_RESULT_ID, "STRUCTURE", null, 3L, null, null);

        assertEquals(TASK_ID, vo.getTaskId());
        assertFalse(vo.getLatest());
        assertEquals(3, vo.getDocPage());
        assertTrue(vo.getItems().isEmpty());
        verify(fileStorage, never()).getObject(any());
    }

    /**
     * 守护 {@code historyTaskShouldReturnOwnContent}：该场景行为改动即失败。
     */
    @Test
    void historyTaskShouldReturnOwnContent() {
        // 历史任务按 task.productId 精确取自己的产物内容（不再仅最新任务可展示）
        when(fileResultDbService.getById(FILE_RESULT_ID)).thenReturn(fileResultOfKb10());
        WbPipelineTask task = latestTask(PipelineStage.CHUNK.name());
        when(pipelineTaskDbService.getById(TASK_ID)).thenReturn(task);
        WbPipelineProduct product = productRow("art-c1");
        when(pipelineProductDbService.getById(PRODUCT_ID)).thenReturn(product);
        WbChunkSet chunkSet = new WbChunkSet();
        chunkSet.setId(70L);
        when(chunkSetDbService.getByArtifactId("art-c1")).thenReturn(chunkSet);
        WbChunk chunk = new WbChunk();
        chunk.setChunkId("chunk-0001");
        chunk.setContent("正文片");
        chunk.setContentType("PARAGRAPH");
        chunk.setOrderNo(1);
        when(chunkDbService.pageByChunkSetId(eq(70L), any(), eq(1L), eq(200L)))
                .thenReturn(new Page<WbChunk>(1, 200).setRecords(List.of(chunk)).setTotal(1));

        StageContentVO vo = service.stageContent(FILE_RESULT_ID, "CHUNK", TASK_ID, null, null, null);

        assertTrue(vo.getLatest());
        assertEquals(1, vo.getItems().size());
        assertEquals("正文片", vo.getItems().get(0).getDisplay());
    }

    /**
     * 守护 {@code unconfiguredStorageShouldPropagate40455}：该场景行为改动即失败。
     */
    @Test
    void unconfiguredStorageShouldPropagate40455() {
        when(fileResultDbService.getById(FILE_RESULT_ID)).thenReturn(fileResultOfKb10());
        when(pipelineTaskDbService.getByFileResultIdAndStage(FILE_RESULT_ID, PipelineStage.PREPROCESS.name()))
                .thenReturn(latestTask(PipelineStage.PREPROCESS.name()));
        // 产物行缺 storageType/storageSourceId/bucket → 记录所属后端未配置：存储类读取失败向上抛，不吞成空条目
        WbPipelineProduct product = new WbPipelineProduct();
        product.setArtifactId("art-p1");
        when(pipelineProductDbService.getById(PRODUCT_ID)).thenReturn(product);

        WorkbuddyException e = assertThrows(WorkbuddyException.class,
                () -> service.stageContent(FILE_RESULT_ID, "PREPROCESS", null, null, null, null));

        assertEquals(ErrorCode.STORAGE_BACKEND_UNCONFIGURED, e.getErrorCode());
    }

    /**
     * 守护 {@code nonStorageReadFailureShouldLeaveItemsEmpty}：该场景行为改动即失败。
     */
    @Test
    void nonStorageReadFailureShouldLeaveItemsEmpty() {
        when(fileResultDbService.getById(FILE_RESULT_ID)).thenReturn(fileResultOfKb10());
        when(pipelineTaskDbService.getByFileResultIdAndStage(FILE_RESULT_ID, PipelineStage.PREPROCESS.name()))
                .thenReturn(latestTask(PipelineStage.PREPROCESS.name()));
        WbPipelineProduct product = productRow("art-p1");
        when(pipelineProductDbService.getById(PRODUCT_ID)).thenReturn(product);
        // 非存储类读取失败：记日志留空条目，不阻断（latest 仍为 true，total 归零）
        when(fileStorage.getObject(ObjectRef.ofProduct(product))).thenThrow(new IllegalStateException("boom"));

        StageContentVO vo = service.stageContent(FILE_RESULT_ID, "PREPROCESS", null, null, null, null);

        assertTrue(vo.getLatest());
        assertTrue(vo.getItems().isEmpty());
        assertEquals(0, vo.getTotal());
    }

    /**
     * 产物对象缺失：PARSE / STRUCTURE / PREPROCESS 三个环节都返回空内容而不抛错。
     *
     * <p>守护 {@code parseItems}/{@code structureItems}/{@code preprocessItems} 的产物缺失兜底：
     * 空集合换成异常或非空都会让页面在"产物被清理"时崩掉。
     */
    @Test
    void stageContentWithMissingArtifactShouldReturnEmptyItems() {
        when(fileResultDbService.getById(FILE_RESULT_ID)).thenReturn(fileResultOfKb10());
        when(pipelineProductDbService.getById(PRODUCT_ID)).thenReturn(productRow("art"));
        when(pipelineTaskDbService.getByFileResultIdAndStage(eq(FILE_RESULT_ID), any()))
                .thenReturn(latestTask(PipelineStage.PARSE.name()));
        when(fileStorage.getObject(any())).thenReturn(null);

        for (String stage : List.of("PARSE", "STRUCTURE", "PREPROCESS")) {
            StageContentVO vo = service.stageContent(FILE_RESULT_ID, stage, null, null, null, null);
            assertTrue(vo.getItems().isEmpty(), stage);
            assertEquals(0, vo.getTotal(), stage);
            assertFalse(vo.getTruncated(), stage);
        }
    }

    /**
     * 环节中文标签随任务解析下发（用于日志与提示）。
     *
     * <p>守护 {@code stageLabel}：标签恒为空串会让任务解析的提示失去环节信息。
     */
    @Test
    void stageContentShouldPassStageLabelToTaskResolution() {
        when(fileResultDbService.getById(FILE_RESULT_ID)).thenReturn(fileResultOfKb10());
        TaskDetailSupport detailSupport = mock(TaskDetailSupport.class);
        when(detailSupport.resolveTask(eq(FILE_RESULT_ID), eq(PipelineStage.PARSE), eq(TASK_ID), any()))
                .thenReturn(null);
        FileResultAccessGuard accessGuard = new FileResultAccessGuard(fileResultDbService, knowledgeBaseDbService);
        StageContentQueryServiceImpl withMockedDetail = new StageContentQueryServiceImpl(pipelineProductDbService,
                chunkSetDbService, chunkDbService, mock(WbEmbeddingSetDbService.class),
                mock(WbEmbeddingRecordDbService.class), fileStorage, detailSupport
                        , new StructureStageContentSupport(new KnowledgeConverterImpl())
                        , new ParseStageContentSupport(new KnowledgeConverterImpl())
                        , new EmbedStageContentSupport(new KnowledgeConverterImpl())
                        , new ChunkStageContentSupport(new KnowledgeConverterImpl()), accessGuard);

        withMockedDetail.stageContent(FILE_RESULT_ID, "PARSE", TASK_ID, null, null, null);

        ArgumentCaptor<String> labelCaptor = ArgumentCaptor.forClass(String.class);
        verify(detailSupport).resolveTask(eq(FILE_RESULT_ID), eq(PipelineStage.PARSE), eq(TASK_ID),
                labelCaptor.capture());
        assertEquals(PipelineStage.PARSE.getLabel(), labelCaptor.getValue());
    }
}
