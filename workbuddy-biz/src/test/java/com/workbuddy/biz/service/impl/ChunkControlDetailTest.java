package com.workbuddy.biz.service.impl;

import com.workbuddy.common.domain.entity.WbChunk;
import com.workbuddy.common.domain.entity.WbChunkSet;
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.domain.preprocess.PreprocessView;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.common.dto.response.chunk.ChunkDetailVO;
import com.workbuddy.common.dto.response.chunk.ChunkStatsVO;
import com.workbuddy.common.enums.preprocess.ViewElementStatus;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 切片详情用例：集合摘要与片列表 / 产物侧来源信息与阶段结论 / 策略快照口径 / 空任务与错误码。
 *
 * <p>触发用例在 {@link ChunkControlTriggerTest}；装配与夹具数据见 {@link ChunkControlTestFixture}。
 */
@ExtendWith(MockitoExtension.class)
class ChunkControlDetailTest {

    private ChunkControlTestFixture fixture;

    private ChunkControlServiceImpl service;

    @BeforeEach
    void setUp() {
        fixture = new ChunkControlTestFixture();
        service = fixture.service();
    }

    /**
     * 切片详情：集合摘要与片列表按集合行装配。
     *
     * <p>守护 {@code chunkDetail}：摘要或片列表漏字段即失败。
     */
    @Test
    void detailShouldAssembleChunkSetSummaryAndChunks() {
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        WbPipelineTask task = new WbPipelineTask();
        task.setId(41L);
        task.setStage(PipelineStage.CHUNK.name());
        task.setStatus(PipelineTaskStatus.SUCCESS.name());
        task.setProductId(50L);
        when(fixture.pipelineTaskDbService.getByFileResultIdAndStage(10L, PipelineStage.CHUNK.name())).thenReturn(task);
        WbPipelineProduct product = new WbPipelineProduct();
        product.setArtifactId("abc".repeat(16));
        // 产物行记下对象位置：详情按它取切片集合
        product.setStorageType(StorageType.MINIO.getCode());
        product.setStorageSourceId(ChunkControlTestFixture.SOURCE_ID);
        product.setBucket("artifacts");
        when(fixture.pipelineProductDbService.getById(50L)).thenReturn(product);
        WbChunkSet chunkSet = new WbChunkSet();
        chunkSet.setId(70L);
        chunkSet.setChunkCount(2);
        chunkSet.setTotalChars(300);
        chunkSet.setArtifactId("abc".repeat(16));
        chunkSet.setStorageType(StorageType.MINIO.getCode());
        chunkSet.setStorageSourceId(ChunkControlTestFixture.SOURCE_ID);
        chunkSet.setBucket("artifacts");
        when(fixture.chunkSetDbService.getByArtifactId("abc".repeat(16))).thenReturn(chunkSet);
        WbChunk parent = new WbChunk();
        parent.setChunkId("c-1");
        parent.setContentType("SECTION");
        parent.setContent("章节父片");
        parent.setTitlePath("第一章");
        parent.setOrderNo(1);
        parent.setCharCount(200);
        WbChunk child = new WbChunk();
        child.setChunkId("c-1-1");
        child.setParentChunkId("c-1");
        child.setContentType("TABLE");
        child.setContent("表格子片");
        child.setOrderNo(2);
        child.setCharCount(100);
        when(fixture.chunkDbService.listByChunkSetId(70L)).thenReturn(List.of(parent, child));

        ChunkDetailVO detail = service.chunkDetail(10L, null);

        assertEquals(41L, detail.getTaskId());
        assertEquals(PipelineTaskStatus.SUCCESS.name(), detail.getStatus());
        assertNotNull(detail.getSummary());
        assertEquals(2, detail.getSummary().getChunkCount());
        assertEquals(300, detail.getSummary().getTotalChars());
        assertEquals(150, detail.getSummary().getAvgChars());
        assertEquals(1, detail.getSummary().getParentChunkCount());
        assertEquals(1, detail.getSummary().getChildChunkCount());
        assertEquals(2, detail.getSummary().getTypeCounts().size());
        assertEquals(2, detail.getChunks().size());
        assertEquals("c-1-1", detail.getChunks().get(1).getChunkId());
        assertEquals("c-1", detail.getChunks().get(1).getParentChunkId());
    }

    /**
     * 详情：产物侧的来源元素个数与兜底原因按 chunkId 对齐下发，阶段统计与结论现算。
     *
     * <p>守护 {@code productChunks}/{@code readUpstreamView}/{@code setStageSummary}：
     * 产物片索引丢了会让详情的来源信息整片空白；上游视图没读到会让"进入切片/跳过"口径缺失。
     */
    @Test
    void detailShouldExposeProductSideFieldsAndStageSummary() {
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        WbPipelineTask task = new WbPipelineTask();
        task.setId(41L);
        task.setStage(PipelineStage.CHUNK.name());
        task.setStatus(PipelineTaskStatus.SUCCESS.name());
        task.setProductId(50L);
        task.setUpstreamProductId(60L);
        when(fixture.pipelineTaskDbService.getByFileResultIdAndStage(10L, PipelineStage.CHUNK.name())).thenReturn(task);
        WbPipelineProduct product = fixture.productOf("art-chunk");
        when(fixture.pipelineProductDbService.getById(50L)).thenReturn(product);
        WbPipelineProduct upstream = fixture.productOf("art-view");
        when(fixture.pipelineProductDbService.getById(60L)).thenReturn(upstream);
        // 产物里的片：c-1 两条（归并取第一条）+ 空 ID 一条（索引时被过滤）
        fixture.stubArtifact(product, fixture.chunkSetOf(fixture.chunk("c-1", List.of("e1", "e2"), "FIXED_LEN"),
                fixture.chunk("c-1", List.of(), "OTHER"), fixture.chunk("", List.of("e9"), null)));
        PreprocessView view = new PreprocessView();
        ViewElement kept = new ViewElement();
        kept.setStatus(ViewElementStatus.NORMAL.name());
        ViewElement skipped = new ViewElement();
        skipped.setStatus(ViewElementStatus.EXCLUDED_HEADER.name());
        view.setElements(new ArrayList<>(List.of(kept, skipped)));
        fixture.stubArtifact(upstream, view);
        WbChunkSet chunkSet = new WbChunkSet();
        chunkSet.setId(70L);
        chunkSet.setArtifactId("art-chunk");
        chunkSet.setChunkCount(3);
        chunkSet.setTotalChars(30);
        when(fixture.chunkSetDbService.getByArtifactId("art-chunk")).thenReturn(chunkSet);
        WbChunk first = new WbChunk();
        first.setChunkId("c-1");
        first.setContentType("PARAGRAPH");
        first.setContent("正文");
        first.setOrderNo(1);
        first.setCharCount(10);
        WbChunk blankId = new WbChunk();
        blankId.setChunkId("");
        blankId.setContentType("PARAGRAPH");
        blankId.setContent("正文二");
        blankId.setOrderNo(2);
        blankId.setCharCount(10);
        when(fixture.chunkDbService.listByChunkSetId(70L)).thenReturn(List.of(first, blankId));

        ChunkDetailVO detail = service.chunkDetail(10L, null);

        assertEquals(2, detail.getChunks().size());
        // c-1 取产物里第一条：来源 2 个、兜底原因 FIXED_LEN
        assertEquals(2, detail.getChunks().get(0).getSourceElementCount());
        assertEquals("FIXED_LEN", detail.getChunks().get(0).getFallbackReason());
        // 空 chunkId 不在产物索引里：来源信息留空
        assertNull(detail.getChunks().get(1).getSourceElementCount());
        // 阶段统计与结论：3 片、上游 2 个元素其中 1 个被剔除
        assertNotNull(detail.getStageSummary());
        assertTrue(detail.getStageSummary().contains("无同节邻居"), detail.getStageSummary());
        assertEquals(1, detail.getStageStats().getRoutedElementCount());
        assertEquals(1, detail.getStageStats().getSkippedElementCount());
    }

    /**
     * 切片详情：策略快照可解析时下发片长上限等口径。
     *
     * <p>守护 {@code chunkDetail}：策略类口径缺失即失败。
     */
    @Test
    void detailWithValidStrategySnapshotShouldExposeStrategyStats() {
        fixture.stubDetail(fixture.taskWithStrategySnapshot("{\"name\":\"chunk-hybrid\",\"version\":\"v1\"}"));

        ChunkDetailVO detail = service.chunkDetail(10L, null);

        ChunkStatsVO stats = detail.getStageStats();
        assertNotNull(stats);
        assertEquals(1, stats.getChunkCount());
        // 策略可解析：片长上限与兜底参数照常下发
        assertNotNull(stats.getTargetMaxLen());
        assertNotNull(stats.getSoftMaxLen());
        assertNotNull(stats.getFallbackLen());
    }

    /**
     * 切片详情：策略快照损坏时策略类口径留空且不炸。
     *
     * <p>守护 {@code chunkDetail}：抛异常给用户即失败。
     */
    @Test
    void detailWithCorruptStrategySnapshotShouldLeaveStrategyStatsEmpty() {
        fixture.stubDetail(fixture.taskWithStrategySnapshot("{oops"));

        ChunkDetailVO detail = service.chunkDetail(10L, null);

        ChunkStatsVO stats = detail.getStageStats();
        assertNotNull(stats);
        assertEquals(1, stats.getChunkCount());
        // 策略快照损坏：策略类口径整组留空（页面上如实表现为无策略快照），不炸也不造默认
        assertNull(stats.getTargetMaxLen());
        assertNull(stats.getSoftMaxLen());
        assertNull(stats.getFallbackLen());
        assertNull(stats.getMinMergeLen());
    }

    /**
     * 切片详情：无任务时片列表为空、不读产物。
     *
     * <p>守护 {@code chunkDetail}：无任务仍读产物即失败。
     */
    @Test
    void detailWithoutTaskShouldReturnEmptyChunks() {
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        when(fixture.pipelineTaskDbService.getByFileResultIdAndStage(10L, PipelineStage.CHUNK.name()))
                .thenReturn(null);

        ChunkDetailVO detail = service.chunkDetail(10L, null);

        assertNotNull(detail.getChunks());
        assertTrue(detail.getChunks().isEmpty());
    }

    /**
     * 切片详情：指定任务环节不符按 40001 拒绝。
     *
     * <p>守护 {@code chunkDetail}：错环节任务被展示即失败。
     */
    @Test
    void detailWithWrongStageTaskShouldReject() {
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        WbPipelineTask preprocessTask = new WbPipelineTask();
        preprocessTask.setId(31L);
        preprocessTask.setFileResultId(10L);
        preprocessTask.setStage(PipelineStage.PREPROCESS.name());
        when(fixture.pipelineTaskDbService.getById(31L)).thenReturn(preprocessTask);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.chunkDetail(10L, 31L));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
    }

    /**
     * 切片详情：产物对象位置不全按 40455 拒绝。
     *
     * <p>守护 {@code chunkDetail}：半截位置去读对象即失败。
     */
    @Test
    void detailWithIncompleteProductLocationShouldReject40455() {
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        WbPipelineTask task = new WbPipelineTask();
        task.setId(41L);
        task.setStage(PipelineStage.CHUNK.name());
        task.setStatus(PipelineTaskStatus.SUCCESS.name());
        task.setProductId(50L);
        when(fixture.pipelineTaskDbService.getByFileResultIdAndStage(10L, PipelineStage.CHUNK.name())).thenReturn(task);
        // 产物行缺 storageType / storageSourceId / bucket：位置不完整，详情按「存储后端未配置」拒绝
        WbPipelineProduct product = new WbPipelineProduct();
        product.setArtifactId("abc".repeat(16));
        when(fixture.pipelineProductDbService.getById(50L)).thenReturn(product);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.chunkDetail(10L, null));

        assertEquals(ErrorCode.STORAGE_BACKEND_UNCONFIGURED, e.getErrorCode());
        verify(fixture.chunkSetDbService, never()).getByArtifactId(any());
    }
}
