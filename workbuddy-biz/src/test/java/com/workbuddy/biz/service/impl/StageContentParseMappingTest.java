package com.workbuddy.biz.service.impl;

import com.workbuddy.biz.service.db.WbChunkDbService;
import com.workbuddy.biz.service.db.WbChunkSetDbService;
import com.workbuddy.biz.service.db.WbEmbeddingRecordDbService;
import com.workbuddy.biz.service.db.WbEmbeddingSetDbService;
import com.workbuddy.biz.service.db.WbPipelineProductDbService;
import com.workbuddy.biz.service.support.access.FileResultAccessGuard;
import com.workbuddy.biz.service.support.common.TaskDetailSupport;
import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.dto.response.stagecontent.StageContentExtraVO;
import com.workbuddy.common.dto.response.stagecontent.StageContentVO;
import com.workbuddy.common.enums.parse.ElementType;
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
import static com.workbuddy.biz.service.impl.StageContentQueryTestFixtures.latestTask;
import static com.workbuddy.biz.service.impl.StageContentQueryTestFixtures.parseArtifact;
import static com.workbuddy.biz.service.impl.StageContentQueryTestFixtures.parseArtifactWithBbox;
import static com.workbuddy.biz.service.impl.StageContentQueryTestFixtures.parseArtifactWithTable;
import static com.workbuddy.biz.service.impl.StageContentQueryTestFixtures.parseResult;
import static com.workbuddy.biz.service.impl.StageContentQueryTestFixtures.productRow;
import static com.workbuddy.biz.service.impl.StageContentQueryTestFixtures.stubArtifact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 解析环节映射单测：sources 平铺元素、表格单元格、边界框与 Office 定位锚点。
 *
 * <p>守护解析环节 Support 的字段搬运：顺序号错位、表格/坐标漏下发都会让对比视图失真。
 */
@ExtendWith(MockitoExtension.class)
class StageContentParseMappingTest {

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
     * 守护 {@code parseExtraShouldCarryTableCells}：该场景行为改动即失败。
     */
    @Test
    void parseExtraShouldCarryTableCells() {
        stubTask();
        stubArtifact(fileStorage, "art", parseArtifactWithTable());

        StageContentVO vo = service.stageContent(FILE_RESULT_ID, "PARSE", null, null, null, null);

        StageContentExtraVO extra = vo.getItems().get(0).getExtra();
        String cells = extra.getCells();
        assertNotNull(cells);
        assertTrue(cells.contains("Contract No."), () -> "cells=" + cells);
        assertTrue(cells.contains("ABC-1234"), () -> "cells=" + cells);
    }

    /**
     * 守护 {@code parseExtraShouldCarryBbox}：该场景行为改动即失败。
     */
    @Test
    void parseExtraShouldCarryBbox() {
        stubTask();
        stubArtifact(fileStorage, "art", parseArtifactWithBbox(2));

        StageContentVO vo = service.stageContent(FILE_RESULT_ID, "PARSE", null, null, null, null);

        StageContentExtraVO first = vo.getItems().get(0).getExtra();
        assertEquals(1, first.getPage().intValue());
        var bbox = first.getBbox();
        assertNotNull(bbox);
        assertEquals(72.0, bbox.getX());
        assertEquals(100.0, bbox.getY());
        assertEquals(400.0, bbox.getWidth());
        assertEquals(20.0, bbox.getHeight());
    }

    /**
     * 守护 {@code elementWithoutBboxShouldNotCarryTheField}：该场景行为改动即失败。
     */
    @Test
    void elementWithoutBboxShouldNotCarryTheField() {
        stubTask();
        stubArtifact(fileStorage, "art", parseArtifact(2));

        StageContentVO vo = service.stageContent(FILE_RESULT_ID, "PARSE", null, null, null, null);

        assertNull(vo.getItems().get(0).getExtra().getBbox());
    }

    /**
     * 守护 {@code parseItemsShouldCarrySeqTypeAndDisplay}：顺序号错位会让两侧对齐失效。
     */
    @Test
    void parseItemsShouldCarrySeqTypeAndDisplay() {
        stubTask();
        ParseElement first = ParseElement.of("p-1", ElementType.PARAGRAPH);
        first.setText("第一段");
        ParseElement second = ParseElement.of("t-1", ElementType.TABLE);
        second.setText("表格");
        stubArtifact(fileStorage, "art", parseResult(List.of(first, second)));

        StageContentVO vo = service.stageContent(FILE_RESULT_ID, "PARSE", null, null, null, null);

        assertEquals(2, vo.getItems().size());
        assertEquals(1, vo.getItems().get(0).getSeq());
        assertEquals("p-1", vo.getItems().get(0).getAlignKey());
        assertEquals(ElementType.PARAGRAPH.name(), vo.getItems().get(0).getType());
        assertEquals("第一段", vo.getItems().get(0).getDisplay());
        assertEquals(2, vo.getItems().get(1).getSeq());
        assertEquals(ElementType.TABLE.name(), vo.getItems().get(1).getType());
    }
}
