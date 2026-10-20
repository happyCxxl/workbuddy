package com.workbuddy.biz.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workbuddy.biz.service.db.WbChunkDbService;
import com.workbuddy.biz.service.db.WbChunkSetDbService;
import com.workbuddy.biz.service.db.WbEmbeddingRecordDbService;
import com.workbuddy.biz.service.db.WbEmbeddingSetDbService;
import com.workbuddy.biz.service.db.WbPipelineProductDbService;
import com.workbuddy.biz.service.support.FileResultAccessGuard;
import com.workbuddy.biz.service.support.TaskDetailSupport;
import com.workbuddy.common.domain.entity.WbChunk;
import com.workbuddy.common.domain.entity.WbChunkSet;
import com.workbuddy.common.domain.entity.WbEmbeddingRecord;
import com.workbuddy.common.domain.entity.WbEmbeddingSet;
import com.workbuddy.common.domain.preprocess.NormalizedField;
import com.workbuddy.common.domain.preprocess.TraceEntry;
import com.workbuddy.common.domain.preprocess.ViewCell;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.common.dto.response.stagecontent.StageContentExtraVO;
import com.workbuddy.common.dto.response.stagecontent.StageContentVO;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.filecenter.storage.FileStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static com.workbuddy.biz.service.impl.StageContentQueryTestFixtures.FILE_RESULT_ID;
import static com.workbuddy.biz.service.impl.StageContentQueryTestFixtures.PRODUCT_ID;
import static com.workbuddy.biz.service.impl.StageContentQueryTestFixtures.latestTask;
import static com.workbuddy.biz.service.impl.StageContentQueryTestFixtures.preprocessView;
import static com.workbuddy.biz.service.impl.StageContentQueryTestFixtures.productRow;
import static com.workbuddy.biz.service.impl.StageContentQueryTestFixtures.stubArtifact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 产物内容查询的环节映射单测：预处理/切片/向量化条目的逐字段映射与空产物兜底。
 *
 * <p>守护各环节 Support 的字段搬运与顺序号口径：错位、漏字段或空集合报错都会让对比视图失真；
 * 解析环节的表格/坐标映射见 {@link StageContentParseMappingTest}。
 */
@ExtendWith(MockitoExtension.class)
class StageContentStageMappingTest {

    @Mock
    private WbPipelineProductDbService pipelineProductDbService;
    @Mock
    private WbChunkSetDbService chunkSetDbService;
    @Mock
    private WbChunkDbService chunkDbService;
    @Mock
    private WbEmbeddingSetDbService embeddingSetDbService;
    @Mock
    private WbEmbeddingRecordDbService embeddingRecordDbService;
    @Mock
    private FileStorage fileStorage;
    @Mock
    private TaskDetailSupport detailSupport;
    @Mock
    private FileResultAccessGuard accessGuard;

    private StageContentQueryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new StageContentQueryServiceImpl(pipelineProductDbService, chunkSetDbService, chunkDbService,
                embeddingSetDbService, embeddingRecordDbService, fileStorage, detailSupport, accessGuard);
    }

    /** 任务解析替身：返回固定任务，聚焦本用例的条目映射 */
    private void stubTask() {
        when(detailSupport.resolveTask(anyLong(), any(), any(), any()))
                .thenReturn(latestTask(PipelineStage.PARSE.name()));
    }

    /** 产物行替身：固定产物 ID，产物键由入参决定 */
    private void stubProduct(String artifactId) {
        when(pipelineProductDbService.getById(PRODUCT_ID)).thenReturn(productRow(artifactId));
    }

    /**
     * 守护 {@code preprocessShouldMapDisplayNormalizedAndStatus}：该场景行为改动即失败。
     */
    @Test
    void preprocessShouldMapDisplayNormalizedAndStatus() {
        stubTask();
        stubProduct("art-p1");
        ViewElement kept = new ViewElement();
        kept.setElementId("el-1");
        kept.setType("PARAGRAPH");
        kept.setStatus("KEPT");
        kept.setDisplayText("投标总价 壹仟贰佰");
        kept.setNormalizedText("投标总价 1200");
        ViewElement excluded = new ViewElement();
        excluded.setElementId("el-2");
        excluded.setType("HEADER");
        excluded.setStatus("EXCLUDED_HEADER");
        excluded.setDisplayText("第 1 页 共 10 页");
        excluded.setNormalizedText(null);
        stubArtifact(fileStorage, "art-p1", preprocessView(List.of(kept, excluded)));

        StageContentVO vo = service.stageContent(FILE_RESULT_ID, "PREPROCESS", null, null, null, null);

        assertTrue(vo.getLatest());
        assertEquals(2, vo.getItems().size());
        assertEquals("el-1", vo.getItems().get(0).getAlignKey());
        assertEquals("投标总价 壹仟贰佰", vo.getItems().get(0).getDisplay());
        assertEquals("投标总价 1200", vo.getItems().get(0).getNormalized());
        assertEquals("KEPT", vo.getItems().get(0).getStatus());
        assertNull(vo.getItems().get(1).getNormalized());
        assertEquals("EXCLUDED_HEADER", vo.getItems().get(1).getStatus());
    }

    /**
     * 守护 {@code preprocessItems}/{@code nonKeepTrace}：把 KEEP 也下发会让响应体成倍膨胀。
     */
    @Test
    void preprocessItemsShouldCarryOptionalExtrasAndFilterKeepTrace() {
        stubTask();
        stubProduct("art");
        ViewElement rich = new ViewElement();
        rich.setElementId("e-1");
        rich.setType("PARAGRAPH");
        rich.setStatus("EXCLUDED_TOC");
        rich.setDisplayText("展示");
        rich.setNormalizedText("检索");
        rich.setRawText("原文");
        rich.setMarks(new ArrayList<>(List.of("REPEATED_PAGE")));
        NormalizedField field = new NormalizedField();
        field.setField("金额");
        field.setValue("1");
        rich.setNormalizedFields(new ArrayList<>(List.of(field)));
        ViewCell cell = new ViewCell();
        cell.setCellId("c-1");
        cell.setText("单元格");
        rich.setCells(new ArrayList<>(List.of(cell)));
        rich.setPage(7);
        TraceEntry keep = new TraceEntry();
        keep.setAction(TraceEntry.ACTION_KEEP);
        keep.setRule("encoding-utf8");
        TraceEntry changed = new TraceEntry();
        changed.setAction("REPLACE");
        changed.setRule("text-tidy-space");
        rich.setPreprocessTrace(new ArrayList<>(List.of(keep, changed)));
        ViewElement plain = new ViewElement();
        plain.setElementId("e-2");
        stubArtifact(fileStorage, "art", preprocessView(List.of(rich, plain)));

        StageContentVO vo = service.stageContent(FILE_RESULT_ID, "PREPROCESS", null, null, null, null);

        var item = vo.getItems().getFirst();
        assertEquals("e-1", item.getAlignKey());
        assertEquals(1, item.getSeq());
        assertEquals("PARAGRAPH", item.getType());
        assertEquals("EXCLUDED_TOC", item.getStatus());
        assertEquals("展示", item.getDisplay());
        assertEquals("检索", item.getNormalized());
        StageContentExtraVO extra = item.getExtra();
        assertEquals("原文", extra.getRawText());
        assertEquals(List.of("REPEATED_PAGE"), extra.getMarks());
        assertTrue(extra.getNormalizedFields().contains("金额"));
        assertTrue(extra.getCells().contains("单元格"));
        assertEquals(7, extra.getPage().intValue());
        String trace = extra.getTrace();
        assertTrue(trace.contains("text-tidy-space"), trace);
        assertFalse(trace.contains("encoding-utf8"), trace);
        assertEquals(2, vo.getItems().get(1).getSeq());
        StageContentExtraVO plainExtra = vo.getItems().get(1).getExtra();
        assertNull(plainExtra.getRawText());
        assertNull(plainExtra.getMarks());
        assertNull(plainExtra.getTrace());
    }

    /**
     * 守护 {@code chunkShouldMapOrderAlignedItems}：该场景行为改动即失败。
     */
    @Test
    void chunkShouldMapOrderAlignedItems() {
        stubTask();
        stubProduct("art-c1");
        WbChunkSet chunkSet = new WbChunkSet();
        chunkSet.setId(70L);
        when(chunkSetDbService.getByArtifactId("art-c1")).thenReturn(chunkSet);
        WbChunk chunk = new WbChunk();
        chunk.setChunkId("chunk-0001");
        chunk.setContent("正文片");
        chunk.setContentType("PARAGRAPH");
        chunk.setTitlePath("第一章");
        chunk.setPageRange("1-2");
        chunk.setCharCount(3);
        chunk.setTokenCount(2);
        chunk.setOrderNo(1);
        when(chunkDbService.pageByChunkSetId(eq(70L), any(), eq(1L), eq(200L)))
                .thenReturn(new Page<WbChunk>(1, 200).setRecords(List.of(chunk)).setTotal(1));

        StageContentVO vo = service.stageContent(FILE_RESULT_ID, "CHUNK", null, null, null, null);

        assertEquals(1, vo.getTotal());
        assertEquals(1, vo.getItems().size());
        assertEquals("chunk-0001", vo.getItems().get(0).getAlignKey());
        assertEquals("正文片", vo.getItems().get(0).getDisplay());
        assertEquals("第一章", vo.getItems().get(0).getExtra().getTitlePath());
        assertEquals("1-2", vo.getItems().get(0).getExtra().getPageRange());
    }

    /**
     * 守护 {@code chunkItemsShouldMapOrderSeqAndAlignKey}：对齐键与顺序号错位会让详情侧对不上片。
     */
    @Test
    void chunkItemsShouldMapOrderSeqAndAlignKey() {
        stubTask();
        stubProduct("art-chunk");
        WbChunkSet chunkSet = new WbChunkSet();
        chunkSet.setId(60L);
        when(chunkSetDbService.getByArtifactId("art-chunk")).thenReturn(chunkSet);
        WbChunk first = new WbChunk();
        first.setChunkId("chunk-0007");
        first.setOrderNo(7);
        first.setContentType("PARAGRAPH");
        first.setContent("正文");
        first.setCharCount(12);
        first.setTokenCount(8);
        first.setTitlePath("第一章");
        first.setPageRange("1-2");
        first.setTableRef(null);
        first.setParentChunkId("chunk-0001");
        WbChunk second = new WbChunk();
        second.setOrderNo(8);
        Page<WbChunk> page = new Page<>(1, 2, 2);
        page.setRecords(List.of(first, second));
        when(chunkDbService.pageByChunkSetId(eq(60L), isNull(), eq(1L), eq(2L))).thenReturn(page);

        StageContentVO vo = service.stageContent(FILE_RESULT_ID, "CHUNK", null, null, 1, 2, null, null);

        assertTrue(vo.getLatest());
        assertEquals(2, vo.getTotal());
        assertEquals(2, vo.getItems().size());
        var item = vo.getItems().getFirst();
        assertEquals(7, item.getSeq());
        assertEquals("chunk-0007", item.getAlignKey());
        assertEquals("PARAGRAPH", item.getType());
        assertEquals("正文", item.getDisplay());
        assertEquals(7, item.getExtra().getOrderNo().intValue());
        assertEquals("chunk-0001", item.getExtra().getParentChunkId());
        assertEquals(8, vo.getItems().get(1).getSeq());
        assertEquals("8", vo.getItems().get(1).getAlignKey());
    }

    /**
     * 守护 {@code fillChunkPage} 的空集合兜底：集合缺失时保留空壳、不读切片表。
     */
    @Test
    void chunkStageWithoutSetShouldReturnEmptyItems() {
        stubTask();
        stubProduct("art-chunk-missing");
        when(chunkSetDbService.getByArtifactId("art-chunk-missing")).thenReturn(null);

        StageContentVO vo = service.stageContent(FILE_RESULT_ID, "CHUNK", null, null, null, null, null, null);

        assertTrue(vo.getItems().isEmpty());
        assertEquals(0, vo.getTotal());
        verify(chunkDbService, never()).pageByChunkSetId(anyLong(), any(), anyLong(), anyLong());
    }

    /**
     * 守护 {@code embedShouldMapRecordsWithCacheHit}：该场景行为改动即失败。
     */
    @Test
    void embedShouldMapRecordsWithCacheHit() {
        stubTask();
        stubProduct("art-e1");
        WbEmbeddingSet set = new WbEmbeddingSet();
        set.setId(90L);
        when(embeddingSetDbService.getByArtifactId("art-e1")).thenReturn(set);
        WbEmbeddingRecord cached = new WbEmbeddingRecord();
        cached.setChunkId("chunk-0001");
        cached.setContentType("PARAGRAPH");
        cached.setInputText("正文片");
        cached.setStatus("CACHED");
        cached.setCacheHit(true);
        cached.setTokenCount(2);
        when(embeddingRecordDbService.listByEmbeddingSetId(90L)).thenReturn(List.of(cached));

        StageContentVO vo = service.stageContent(FILE_RESULT_ID, "EMBED", null, null, null, null);

        assertEquals(1, vo.getItems().size());
        assertEquals("CACHED", vo.getItems().get(0).getStatus());
        assertEquals(Boolean.TRUE, vo.getItems().get(0).getExtra().getCacheHit());
    }

    /**
     * 守护 {@code embedItemsBySet}/{@code itemOf}：顺序号与对齐键错位会让两侧无法互相定位。
     */
    @Test
    void embedItemsShouldMapRecordsInOrder() {
        stubTask();
        stubProduct("art-embed");
        WbEmbeddingSet embeddingSet = new WbEmbeddingSet();
        embeddingSet.setId(70L);
        when(embeddingSetDbService.getByArtifactId("art-embed")).thenReturn(embeddingSet);
        WbEmbeddingRecord first = new WbEmbeddingRecord();
        first.setChunkId("chunk-1");
        first.setContentType("PARAGRAPH");
        first.setStatus("SUCCESS");
        first.setInputText("输入一");
        first.setTokenCount(9);
        first.setCacheHit(Boolean.TRUE);
        first.setRequestId("req-1");
        WbEmbeddingRecord second = new WbEmbeddingRecord();
        second.setChunkId("chunk-2");
        when(embeddingRecordDbService.listByEmbeddingSetId(70L)).thenReturn(List.of(first, second));

        StageContentVO vo = service.stageContent(FILE_RESULT_ID, "EMBED", null, null, null, null);

        assertEquals(2, vo.getItems().size());
        var item = vo.getItems().getFirst();
        assertEquals(1, item.getSeq());
        assertEquals("1", item.getAlignKey());
        assertEquals("PARAGRAPH", item.getType());
        assertEquals("SUCCESS", item.getStatus());
        assertEquals("输入一", item.getDisplay());
        assertEquals("chunk-1", item.getExtra().getChunkId());
        assertEquals(9, item.getExtra().getTokenCount().intValue());
        assertEquals(Boolean.TRUE, item.getExtra().getCacheHit());
        assertEquals("req-1", item.getExtra().getRequestId());
        assertEquals(2, vo.getItems().get(1).getSeq());
    }

    /**
     * 守护 {@code embedItemsBySet} 的空集合兜底：向量段未产出时页面应显示空而不是报错。
     */
    @Test
    void stageContentEmbedWithoutSetShouldReturnEmptyItems() {
        stubTask();
        stubProduct("art");
        when(embeddingSetDbService.getByArtifactId(any())).thenReturn(null);

        StageContentVO vo = service.stageContent(FILE_RESULT_ID, "EMBED", null, null, null, null);

        assertTrue(vo.getItems().isEmpty());
        assertEquals(0, vo.getTotal());
    }
}
