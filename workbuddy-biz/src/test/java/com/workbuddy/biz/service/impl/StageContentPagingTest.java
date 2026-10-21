package com.workbuddy.biz.service.impl;

import com.workbuddy.biz.service.StageContentQueryService;
import com.workbuddy.biz.service.db.WbChunkDbService;
import com.workbuddy.biz.service.db.WbChunkSetDbService;
import com.workbuddy.biz.service.db.WbEmbeddingRecordDbService;
import com.workbuddy.biz.service.db.WbEmbeddingSetDbService;
import com.workbuddy.biz.service.db.WbPipelineProductDbService;
import com.workbuddy.biz.service.support.access.FileResultAccessGuard;
import com.workbuddy.biz.service.support.common.TaskDetailSupport;
import com.workbuddy.common.dto.response.stagecontent.StageContentVO;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.filecenter.storage.FileStorage;
import com.workbuddy.biz.service.support.stagecontent.ChunkStageContentSupport;
import com.workbuddy.biz.service.support.stagecontent.EmbedStageContentSupport;
import com.workbuddy.biz.service.support.stagecontent.ParseStageContentSupport;
import com.workbuddy.biz.service.support.stagecontent.StructureStageContentSupport;
import com.workbuddy.common.convert.KnowledgeConverterImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static com.workbuddy.biz.service.impl.StageContentQueryTestFixtures.FILE_RESULT_ID;
import static com.workbuddy.biz.service.impl.StageContentQueryTestFixtures.PRODUCT_ID;
import static com.workbuddy.biz.service.impl.StageContentQueryTestFixtures.element;
import static com.workbuddy.biz.service.impl.StageContentQueryTestFixtures.latestTask;
import static com.workbuddy.biz.service.impl.StageContentQueryTestFixtures.parseArtifact;
import static com.workbuddy.biz.service.impl.StageContentQueryTestFixtures.productRow;
import static com.workbuddy.biz.service.impl.StageContentQueryTestFixtures.structureDocument;
import static com.workbuddy.biz.service.impl.StageContentQueryTestFixtures.stubArtifact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 产物内容查询的分页单测：分页归一、切片、截断判定与空集合兜底。
 *
 * <p>守护 {@code resolveCurrent}/{@code resolveSize}/{@code slice}/{@code hasMore} 的边界口径：
 * 上限不截断会让单页拉全库，按 {@code page × size < total} 判会误报末页还有内容。
 */
@ExtendWith(MockitoExtension.class)
class StageContentPagingTest {

    @Mock
    private WbPipelineProductDbService pipelineProductDbService;
    @Mock
    private FileStorage fileStorage;
    @Mock
    private TaskDetailSupport detailSupport;
    @Mock
    private FileResultAccessGuard accessGuard;

    private StageContentQueryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new StageContentQueryServiceImpl(pipelineProductDbService, mock(WbChunkSetDbService.class),
                mock(WbChunkDbService.class), mock(WbEmbeddingSetDbService.class),
                mock(WbEmbeddingRecordDbService.class), fileStorage, detailSupport
                        , new StructureStageContentSupport(new KnowledgeConverterImpl())
                        , new ParseStageContentSupport(new KnowledgeConverterImpl())
                        , new EmbedStageContentSupport(new KnowledgeConverterImpl())
                        , new ChunkStageContentSupport(new KnowledgeConverterImpl()), accessGuard);
    }

    /** 任务与产物夹具：产物键固定为 art */
    private void stubTask() {
        when(detailSupport.resolveTask(anyLong(), any(), any(), any()))
                .thenReturn(latestTask(PipelineStage.PARSE.name()));
        when(pipelineProductDbService.getById(PRODUCT_ID)).thenReturn(productRow("art"));
    }

    /**
     * 分页归一：页码非正按第 1 页，每页条数超上限按上限截断、非正按默认值。
     *
     * <p>守护 {@code resolveCurrent}/{@code resolveSize} 的边界：上限不截断会让单页拉全库，
     * 而 1 条/页是合法取值（不能被当成「未传」）。
     */
    @Test
    void stageContentShouldNormalizePageAndLimit() {
        stubTask();
        stubArtifact(fileStorage, "art", parseArtifact(1));

        StageContentVO upper = service.stageContent(FILE_RESULT_ID, "PARSE", null, null, -3, 5000);
        StageContentVO single = service.stageContent(FILE_RESULT_ID, "PARSE", null, null, 1, 1);

        assertEquals(1, upper.getCurrent());
        assertEquals(StageContentQueryService.MAX_SIZE, upper.getSize());
        assertEquals(1, single.getSize());
    }

    /**
     * 空产物（0 元素）：total 为 0 且不标截断。
     *
     * <p>守护 {@code hasMore} 的空集合兜底：0 条还标截断会让页面一直「加载下一页」。
     */
    @Test
    void stageContentWithoutItemsShouldNotMarkTruncation() {
        stubTask();
        stubArtifact(fileStorage, "art", structureDocument(List.of()));

        StageContentVO vo = service.stageContent(FILE_RESULT_ID, "STRUCTURE", null, null, null, null);

        assertEquals(0, vo.getTotal());
        assertTrue(vo.getItems().isEmpty());
        assertFalse(vo.getTruncated());
    }

    /**
     * 翻页与截断：后续还有内容标截断，末页不标，页码越界为空页且不标截断。
     *
     * <p>守护 {@code slice}/{@code hasMore}：按 {@code page × limit < total} 判会误报末页还有内容。
     */
    @Test
    void stageContentShouldPaginateAndMarkTruncation() {
        stubTask();
        stubArtifact(fileStorage, "art", structureDocument(List.of(
                element("e-1", "TITLE", "一"), element("e-2", "PARAGRAPH", "二"),
                element("e-3", "PARAGRAPH", "三"), element("e-4", "PARAGRAPH", "四"),
                element("e-5", "PARAGRAPH", "五"))));

        StageContentVO first = service.stageContent(FILE_RESULT_ID, "STRUCTURE", null, null, 1, 2);
        StageContentVO last = service.stageContent(FILE_RESULT_ID, "STRUCTURE", null, null, 3, 2);
        StageContentVO beyond = service.stageContent(FILE_RESULT_ID, "STRUCTURE", null, null, 9, 2);

        assertEquals(2, first.getItems().size());
        assertEquals(5, first.getTotal());
        assertTrue(first.getTruncated());
        assertEquals(1, last.getItems().size());
        assertFalse(last.getTruncated());
        // 页码越界：空页且后面没有内容，不标截断
        assertTrue(beyond.getItems().isEmpty());
        assertEquals(5, beyond.getTotal());
        assertFalse(beyond.getTruncated());
    }

    /**
     * 守护 {@code pageAndLimitShouldSliceItemsAndReportTotal}：该场景行为改动即失败。
     */
    @Test
    void pageAndLimitShouldSliceItemsAndReportTotal() {
        stubTask();
        stubArtifact(fileStorage, "art", parseArtifact(5));

        StageContentVO first = service.stageContent(FILE_RESULT_ID, "PARSE", null, null, 1, 2);
        assertEquals(5, first.getTotal());
        assertEquals(2, first.getItems().size());
        assertEquals("el-1", first.getItems().get(0).getAlignKey());
        assertEquals(1, first.getCurrent());
        assertEquals(2, first.getSize());
        assertTrue(first.getTruncated());

        StageContentVO last = service.stageContent(FILE_RESULT_ID, "PARSE", null, null, 3, 2);
        assertEquals(5, last.getTotal());
        assertEquals(1, last.getItems().size());
        assertEquals("el-5", last.getItems().get(0).getAlignKey());
        assertFalse(last.getTruncated());
    }

    /**
     * 守护 {@code pagingDefaultsShouldReturnFirstPageWithinLimit}：该场景行为改动即失败。
     */
    @Test
    void pagingDefaultsShouldReturnFirstPageWithinLimit() {
        stubTask();
        stubArtifact(fileStorage, "art", parseArtifact(5));

        // 不传分页参数：按第 1 页 + 默认上限返回，内容与全量一致（向后兼容）
        StageContentVO vo = service.stageContent(FILE_RESULT_ID, "PARSE", null, null, null, null);

        assertEquals(5, vo.getTotal());
        assertEquals(5, vo.getItems().size());
        assertEquals(1, vo.getCurrent());
        assertEquals(StageContentQueryService.DEFAULT_SIZE, vo.getSize());
        assertFalse(vo.getTruncated());
    }

    /**
     * 守护 {@code limitAboveMaxShouldBeCappedAndOverflowPageShouldBeEmpty}：该场景行为改动即失败。
     */
    @Test
    void limitAboveMaxShouldBeCappedAndOverflowPageShouldBeEmpty() {
        stubTask();
        stubArtifact(fileStorage, "art", parseArtifact(5));

        StageContentVO capped = service.stageContent(FILE_RESULT_ID, "PARSE", null, null, 1,
                StageContentQueryService.MAX_SIZE + 500);
        assertEquals(StageContentQueryService.MAX_SIZE, capped.getSize());
        assertEquals(5, capped.getItems().size());

        // 页码越界：内容为空但 total 仍是真实总数；已取到总数之后，不再算"还有内容"
        StageContentVO overflow = service.stageContent(FILE_RESULT_ID, "PARSE", null, null, 99, 2);
        assertTrue(overflow.getItems().isEmpty());
        assertEquals(5, overflow.getTotal());
        assertFalse(overflow.getTruncated());
    }

    /**
     * 守护 {@code lastPartialPageShouldNotBeReportedAsTruncated}：该场景行为改动即失败。
     */
    @Test
    void lastPartialPageShouldNotBeReportedAsTruncated() {
        stubTask();
        stubArtifact(fileStorage, "art", parseArtifact(5));

        // 5 条按每页 2 条取第 3 页：只回 1 条，但已经是最后一页 —— 不能报还有内容
        StageContentVO third = service.stageContent(FILE_RESULT_ID, "PARSE", null, null, 3, 2);
        assertEquals(1, third.getItems().size());
        assertEquals(5, third.getTotal());
        assertFalse(third.getTruncated());

        // 第 2 页取满且后面还有 1 条 —— 要报还有内容
        StageContentVO second = service.stageContent(FILE_RESULT_ID, "PARSE", null, null, 2, 2);
        assertEquals(2, second.getItems().size());
        assertTrue(second.getTruncated());
    }
}
