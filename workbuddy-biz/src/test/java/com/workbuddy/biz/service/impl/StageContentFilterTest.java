package com.workbuddy.biz.service.impl;

import com.workbuddy.biz.service.db.WbChunkDbService;
import com.workbuddy.biz.service.db.WbChunkSetDbService;
import com.workbuddy.biz.service.db.WbEmbeddingRecordDbService;
import com.workbuddy.biz.service.db.WbEmbeddingSetDbService;
import com.workbuddy.biz.service.db.WbPipelineProductDbService;
import com.workbuddy.biz.service.support.FileResultAccessGuard;
import com.workbuddy.biz.service.support.TaskDetailSupport;
import com.workbuddy.common.domain.structure.UnifiedElement;
import com.workbuddy.common.dto.response.stagecontent.StageContentVO;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.filecenter.storage.FileStorage;
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
import static com.workbuddy.biz.service.impl.StageContentQueryTestFixtures.parseArtifactWithBbox;
import static com.workbuddy.biz.service.impl.StageContentQueryTestFixtures.productRow;
import static com.workbuddy.biz.service.impl.StageContentQueryTestFixtures.structureDocument;
import static com.workbuddy.biz.service.impl.StageContentQueryTestFixtures.stubArtifact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 产物内容查询的过滤单测：文档页与处置状态过滤，过滤先于分页。
 *
 * <p>守护 {@code filterByDocPage}/{@code filterByStatus}：类型不同的页码直接比会永远不相等，
 * 第 1 页也不能被当成「未过滤」。
 */
@ExtendWith(MockitoExtension.class)
class StageContentFilterTest {

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
                mock(WbEmbeddingRecordDbService.class), fileStorage, detailSupport, accessGuard);
    }

    /** 任务与产物夹具：产物键固定为 art */
    private void stubTask() {
        when(detailSupport.resolveTask(anyLong(), any(), any(), any()))
                .thenReturn(latestTask(PipelineStage.PARSE.name()));
        when(pipelineProductDbService.getById(PRODUCT_ID)).thenReturn(productRow("art"));
    }

    /**
     * 文档页与处置状态过滤：先过滤再分页，total 按过滤后口径。
     *
     * <p>守护 {@code filterByDocPage}/{@code filterByStatus}：类型不同的页码直接比会永远不相等。
     */
    @Test
    void stageContentShouldFilterByDocPageAndStatus() {
        stubTask();
        UnifiedElement page1 = element("e-0", "PARAGRAPH", "第一页");
        page1.setPage(1);
        UnifiedElement page3 = element("e-1", "PARAGRAPH", "第三页");
        page3.setPage(3);
        page3.setConflictStatus("EXCLUDED_TOC");
        UnifiedElement page4 = element("e-2", "PARAGRAPH", "第四页");
        page4.setPage(4);
        page4.setConflictStatus("REPEATED");
        stubArtifact(fileStorage, "art", structureDocument(List.of(page1, page3, page4)));

        StageContentVO byPage = service.stageContent(FILE_RESULT_ID, "STRUCTURE", null, 3L, null, null);
        // 页码 1 是合法过滤值（不是「不过滤」）
        StageContentVO firstPage = service.stageContent(FILE_RESULT_ID, "STRUCTURE", null, 1L, null, null);
        StageContentVO byStatus = service.stageContent(FILE_RESULT_ID, "STRUCTURE", null, null, null, null,
                "REPEATED", null);
        StageContentVO blankStatus = service.stageContent(FILE_RESULT_ID, "STRUCTURE", null, null, null, null,
                " , ", null);

        assertEquals(1, byPage.getItems().size());
        assertEquals("第三页", byPage.getItems().getFirst().getDisplay());
        assertEquals(1, byPage.getTotal());
        assertEquals(1, firstPage.getItems().size());
        assertEquals("第一页", firstPage.getItems().getFirst().getDisplay());
        assertEquals(1, byStatus.getItems().size());
        assertEquals("REPEATED", byStatus.getItems().getFirst().getStatus());
        assertEquals(3, blankStatus.getItems().size());
    }

    /**
     * 文档页 1 是合法过滤值：回显为 1，而不是被归一成空。
     *
     * <p>守护 {@code stageContent} 的 {@code docPage < 1} 边界：写成小于等于会把第 1 页当未过滤。
     */
    @Test
    void stageContentWithDocPageOneShouldEchoDocPage() {
        stubTask();
        stubArtifact(fileStorage, "art", parseArtifact(1));

        StageContentVO vo = service.stageContent(FILE_RESULT_ID, "PARSE", null, 1L, null, null);

        assertEquals(1, vo.getDocPage());
    }

    /**
     * 守护 {@code docPageShouldNarrowItemsAndTotal}：该场景行为改动即失败。
     */
    @Test
    void docPageShouldNarrowItemsAndTotal() {
        stubTask();
        stubArtifact(fileStorage, "art", parseArtifactWithBbox(5));

        StageContentVO vo = service.stageContent(FILE_RESULT_ID, "PARSE", null, 3L, null, null);

        assertEquals(3, vo.getDocPage());
        assertEquals(1, vo.getTotal());
        assertEquals(1, vo.getItems().size());
        assertEquals("el-3", vo.getItems().get(0).getAlignKey());
        assertFalse(vo.getTruncated());

        // 不传 docPage：total 回到全量
        StageContentVO all = service.stageContent(FILE_RESULT_ID, "PARSE", null, null, null, null);
        assertNull(all.getDocPage());
        assertEquals(5, all.getTotal());
    }

    /**
     * 守护 {@code docPageWithoutMatchShouldReturnEmpty}：该场景行为改动即失败。
     */
    @Test
    void docPageWithoutMatchShouldReturnEmpty() {
        stubTask();
        stubArtifact(fileStorage, "art", parseArtifactWithBbox(2));

        StageContentVO vo = service.stageContent(FILE_RESULT_ID, "PARSE", null, 9L, null, null);

        assertEquals(9, vo.getDocPage());
        assertEquals(0, vo.getTotal());
        assertTrue(vo.getItems().isEmpty());
    }
}
