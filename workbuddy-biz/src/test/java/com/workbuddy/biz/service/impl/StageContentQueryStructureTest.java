package com.workbuddy.biz.service.impl;

import com.workbuddy.biz.service.db.WbChunkDbService;
import com.workbuddy.biz.service.db.WbChunkSetDbService;
import com.workbuddy.biz.service.db.WbEmbeddingRecordDbService;
import com.workbuddy.biz.service.db.WbEmbeddingSetDbService;
import com.workbuddy.biz.service.db.WbFileResultDbService;
import com.workbuddy.biz.service.db.WbKnowledgeBaseDbService;
import com.workbuddy.biz.service.db.WbPipelineProductDbService;
import com.workbuddy.biz.service.db.WbPipelineStepLogDbService;
import com.workbuddy.biz.service.db.WbPipelineTaskDbService;
import com.workbuddy.biz.service.support.TaskDetailSupport;
import com.workbuddy.biz.testkit.SecurityTestSupport;
import com.workbuddy.common.domain.parse.BBox;
import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseResult;
import com.workbuddy.common.domain.structure.UnifiedDocument;
import com.workbuddy.common.domain.structure.UnifiedElement;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.enums.parse.ElementType;
import com.workbuddy.common.enums.knowledge.KnowledgeBaseStatus;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.domain.entity.WbFileResult;
import com.workbuddy.common.domain.entity.WbKnowledgeBase;
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.filecenter.storage.FileStorage;
import com.workbuddy.common.utils.JsonUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 结构化环节内容查询单测。
 *
 * <p>守护 {@code structureItems}/{@code structureExtra}/{@code putOfficeAnchor} 的口径：
 * 元素按序映射 type/status/display，层级、页网格（页/行/列）、边界框与 Office 锚点
 * （样式/工作表/单元格行列跨距/表头标记）按非空并入 extra，缺失字段不下发。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StageContentQueryStructureTest {

    private static final Long SOURCE_ID = 1L;

    @Mock
    private WbPipelineTaskDbService pipelineTaskDbService;

    @Mock
    private WbPipelineProductDbService pipelineProductDbService;

    @Mock
    private WbPipelineStepLogDbService stepLogDbService;

    @Mock
    private WbChunkSetDbService chunkSetDbService;

    @Mock
    private WbChunkDbService chunkDbService;

    @Mock
    private WbEmbeddingSetDbService embeddingSetDbService;

    @Mock
    private WbEmbeddingRecordDbService embeddingRecordDbService;

    @Mock
    private WbFileResultDbService fileResultDbService;

    @Mock
    private WbKnowledgeBaseDbService knowledgeBaseDbService;

    @Mock
    private FileStorage fileStorage;

    private StageContentQueryServiceImpl service;

    /**
     * 每个用例重建登录态与服务（文件结果 10 挂在知识库 10 下）。
     */
    @BeforeEach
    void setUp() {
        SecurityTestSupport.loginViewer();
        WbKnowledgeBase kb = new WbKnowledgeBase();
        kb.setId(10L);
        kb.setUserId(1L);
        when(knowledgeBaseDbService.getActiveById(10L)).thenReturn(kb);
        when(fileResultDbService.getById(10L)).thenReturn(fileResultOfKb10());
        var accessGuard = new com.workbuddy.biz.service.support.FileResultAccessGuard(
                fileResultDbService, knowledgeBaseDbService);
        service = new StageContentQueryServiceImpl(pipelineProductDbService,
                chunkSetDbService, chunkDbService, embeddingSetDbService, embeddingRecordDbService,
                fileStorage, new TaskDetailSupport(pipelineTaskDbService, stepLogDbService, pipelineProductDbService),
                accessGuard);
    }

    /**
     * 收尾清理登录态。
     */
    @AfterEach
    void tearDown() {
        SecurityTestSupport.logout();
    }

    /**
     * 结构化环节：元素按序映射 type/status/display，层级与页网格并入 extra。
     *
     * <p>守护 {@code structureItems}/{@code structureExtra}：字段错位或层级丢失即失败。
     */
    @Test
    void structureItemsShouldMapElementsWithLevelAndPageGrid() {
        stubTask("STRUCTURE", "art-structure");
        UnifiedElement title = new UnifiedElement();
        title.setId("el-title");
        title.setType("TITLE");
        title.setText("第一章 总则");
        title.setLevel(1);
        title.setPage(3);
        title.setRows(1);
        title.setCols(40);
        title.setBbox(new BBox(10, 20, 300, 24));

        UnifiedElement plain = new UnifiedElement();
        plain.setId("el-p");
        plain.setType("PARAGRAPH");
        plain.setText("正文内容");
        plain.setConflictStatus("CONFLICT");
        stubArtifact("art-structure", structureDoc(List.of(title, plain)));

        var vo = service.stageContent(10L, "STRUCTURE", null, null, null, null);

        assertEquals(2, vo.getItems().size());
        var first = vo.getItems().get(0);
        assertEquals("el-title", first.getAlignKey());
        assertEquals("TITLE", first.getType());
        assertEquals("第一章 总则", first.getDisplay());
        assertEquals(1, first.getSeq());
        Map<String, Object> extra = first.getExtra();
        assertEquals(1, extra.get("level"));
        assertEquals(3, extra.get("page"));
        assertEquals(1, extra.get("rows"));
        assertEquals(40, extra.get("cols"));
        @SuppressWarnings("unchecked")
        Map<String, Object> bbox = (Map<String, Object>) extra.get("bbox");
        assertEquals(10.0, bbox.get("x"));
        assertEquals(24.0, bbox.get("height"));
        var second = vo.getItems().get(1);
        assertEquals("CONFLICT", second.getStatus());
        assertEquals(2, second.getSeq());
        assertFalse(second.getExtra().containsKey("level"));
        assertFalse(second.getExtra().containsKey("bbox"));
    }

    /**
     * 解析环节的 Office 锚点：样式、工作表名与单元格行列跨距按非空并入 extra。
     *
     * <p>守护 {@code putOfficeAnchor}：Excel 单元格的行列是高亮定位依据，漏并入即失败。
     */
    @Test
    void parseItemsShouldCarryOfficeAnchorFields() {
        stubTask("PARSE", "art-parse");
        ParseElement cell = ParseElement.of("t1_1_2", ElementType.TABLE_CELL);
        cell.setSheetName("报价表");
        cell.setRow(1);
        cell.setCol(2);
        cell.setRowSpan(2);
        cell.setColSpan(3);
        cell.setIsHeader(true);
        cell.setStyle("Heading 2");
        ParseElement plain = ParseElement.of("p-1", ElementType.PARAGRAPH);
        plain.setText("正文");
        stubArtifact("art-parse", parseDoc(List.of(cell, plain)));

        var vo = service.stageContent(10L, "PARSE", null, null, null, null);

        Map<String, Object> cellExtra = vo.getItems().get(0).getExtra();
        assertEquals("报价表", cellExtra.get("sheetName"));
        assertEquals(1, cellExtra.get("row"));
        assertEquals(2, cellExtra.get("col"));
        assertEquals(2, cellExtra.get("rowSpan"));
        assertEquals(3, cellExtra.get("colSpan"));
        assertEquals(Boolean.TRUE, cellExtra.get("isHeader"));
        Map<String, Object> plainExtra = vo.getItems().get(1).getExtra();
        // 无锚点字段的元素不下发任何 Office 定位键（空白值也不能塞进 extra）
        assertFalse(plainExtra.containsKey("style"));
        assertFalse(plainExtra.containsKey("sheetName"));
        assertFalse(plainExtra.containsKey("row"));
        assertFalse(plainExtra.containsKey("col"));
        assertFalse(plainExtra.containsKey("rowSpan"));
        assertFalse(plainExtra.containsKey("colSpan"));
        assertFalse(plainExtra.containsKey("isHeader"));
    }

    /**
     * 打桩：文件结果 10 的指定环节有成功任务与产物，产物内容为给定对象。
     *
     * @param stage      环节名
     * @param artifactId 产物键
     */
    private void stubTask(String stage, String artifactId) {
        WbPipelineTask task = new WbPipelineTask();
        task.setId(41L);
        task.setFileResultId(10L);
        task.setStage(stage);
        task.setStatus(PipelineTaskStatus.SUCCESS.name());
        task.setProductId(50L);
        when(pipelineTaskDbService.getByFileResultIdAndStage(10L, stage)).thenReturn(task);
        WbPipelineProduct product = new WbPipelineProduct();
        product.setArtifactId(artifactId);
        product.setStorageType(StorageType.MINIO.getCode());
        product.setStorageSourceId(SOURCE_ID);
        product.setBucket("artifacts");
        when(pipelineProductDbService.getById(50L)).thenReturn(product);
    }

    /**
     * 打桩产物内容。
     *
     * @param artifactId 产物键
     * @param value      产物对象
     */
    private void stubArtifact(String artifactId, Object value) {
        WbPipelineProduct product = new WbPipelineProduct();
        product.setArtifactId(artifactId);
        product.setStorageType(StorageType.MINIO.getCode());
        product.setStorageSourceId(SOURCE_ID);
        product.setBucket("artifacts");
        when(fileStorage.getObject(Objects.requireNonNull(ObjectRef.ofProduct(product))))
                .thenReturn(JsonUtil.toJsonStr(value).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 构造结构化产物文档。
     *
     * @param elements 元素
     * @return 文档
     */
    private UnifiedDocument structureDoc(List<UnifiedElement> elements) {
        UnifiedDocument document = new UnifiedDocument();
        document.setElements(elements);
        return document;
    }

    /**
     * 构造解析产物文档（sources 平铺元素）。
     *
     * @param elements 元素
     * @return 解析结果
     */
    private ParseResult parseDoc(List<ParseElement> elements) {
        com.workbuddy.common.domain.parse.ParseSource source = new com.workbuddy.common.domain.parse.ParseSource();
        source.setSource("native");
        source.setProvider("test");
        source.setElements(elements);
        ParseResult result = new ParseResult();
        result.setSources(List.of(source));
        return result;
    }

    /**
     * 构造文件结果行。
     *
     * @return 文件结果
     */
    private WbFileResult fileResultOfKb10() {
        WbFileResult fileResult = new WbFileResult();
        fileResult.setId(10L);
        fileResult.setKnowledgeBaseId(10L);
        return fileResult;
    }
}
