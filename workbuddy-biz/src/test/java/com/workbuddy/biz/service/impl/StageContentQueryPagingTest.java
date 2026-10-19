package com.workbuddy.biz.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workbuddy.biz.service.StageContentQueryService;
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
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseResult;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.domain.preprocess.NormalizedField;
import com.workbuddy.common.domain.preprocess.PreprocessView;
import com.workbuddy.common.domain.preprocess.TraceEntry;
import com.workbuddy.common.domain.preprocess.ViewCell;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.common.domain.structure.UnifiedDocument;
import com.workbuddy.common.domain.structure.UnifiedElement;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.dto.request.stage.ChunkContentFilterDTO;
import com.workbuddy.common.dto.response.stagecontent.StageContentVO;
import com.workbuddy.common.enums.parse.ElementType;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.filecenter.storage.FileStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 环节内容查询的分页与条目映射单测。
 *
 * <p>守护 {@code stageContent} 的外壳字段（任务/产物缺失时的空壳）、分页归一与截断判定、
 * 文档页与处置状态过滤，以及解析/预处理/切片/向量化四类条目的逐字段映射。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StageContentQueryPagingTest {

    private static final Long FILE_RESULT_ID = 10L;

    private static final Long TASK_ID = 41L;

    private static final Long PRODUCT_ID = 50L;

    private static final Long SOURCE_ID = 1L;

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

    /**
     * 每个用例重建服务（访问护栏与任务解析用替身，专注分页与映射）。
     */
    @BeforeEach
    void setUp() {
        service = new StageContentQueryServiceImpl(pipelineProductDbService, chunkSetDbService, chunkDbService,
                embeddingSetDbService, embeddingRecordDbService, fileStorage, detailSupport, accessGuard);
    }

    /**
     * 无任务时返回空壳：外壳字段齐全、条目为空、total 0、未截断。
     *
     * <p>守护 {@code stageContent} 的空任务兜底：外壳字段漏搬会让页面拿不到分页口径。
     */
    @Test
    void stageContentWithoutTaskShouldReturnEmptyScaffold() {
        when(detailSupport.resolveTask(eq(FILE_RESULT_ID), eq(PipelineStage.PARSE), any(), any())).thenReturn(null);

        StageContentVO vo = service.stageContent(FILE_RESULT_ID, "PARSE", null, 0L, 0, 0);

        assertEquals(FILE_RESULT_ID, vo.getFileResultId());
        assertEquals("PARSE", vo.getStage());
        assertFalse(vo.getLatest());
        assertNull(vo.getTaskId());
        // docPage 非正、page 非正、limit 非正都按缺省口径归一
        assertNull(vo.getDocPage());
        assertEquals(1, vo.getPage());
        assertEquals(StageContentQueryService.DEFAULT_LIMIT, vo.getLimit());
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
        stubTask();
        when(pipelineProductDbService.getById(PRODUCT_ID)).thenReturn(null);

        StageContentVO vo = service.stageContent(FILE_RESULT_ID, "STRUCTURE", null, 3L, null, null);

        assertEquals(TASK_ID, vo.getTaskId());
        assertFalse(vo.getLatest());
        assertEquals(3, vo.getDocPage());
        assertTrue(vo.getItems().isEmpty());
        verify(fileStorage, org.mockito.Mockito.never()).getObject(any());
    }

    /**
     * 分页归一：页码非正按第 1 页，每页条数超上限按上限截断、非正按默认值。
     *
     * <p>守护 {@code resolvePage}/{@code resolveLimit} 的边界：上限不截断会让单页拉全库，
     * 而 1 条/页是合法取值（不能被当成「未传」）。
     */
    @Test
    void stageContentShouldNormalizePageAndLimit() {
        stubTask();
        stubArtifact(parseDoc(List.of(ParseElement.of("p-1", ElementType.PARAGRAPH))));

        StageContentVO upper = service.stageContent(FILE_RESULT_ID, "PARSE", null, null, -3, 5000);
        StageContentVO single = service.stageContent(FILE_RESULT_ID, "PARSE", null, null, 1, 1);

        assertEquals(1, upper.getPage());
        assertEquals(StageContentQueryService.MAX_LIMIT, upper.getLimit());
        assertEquals(1, single.getLimit());
    }

    /**
     * 空产物（0 元素）：total 为 0 且不标截断。
     *
     * <p>守护 {@code hasMore} 的空集合兜底：0 条还标截断会让页面一直「加载下一页」。
     */
    @Test
    void stageContentWithoutItemsShouldNotMarkTruncation() {
        stubTask();
        stubArtifact(structureDoc(List.of()));

        StageContentVO vo = service.stageContent(FILE_RESULT_ID, "STRUCTURE", null, null, null, null);

        assertEquals(0, vo.getTotal());
        assertTrue(vo.getItems().isEmpty());
        assertFalse(vo.getTruncated());
    }

    /**
     * 翻页与截断：后续还有内容标截断，末页不标，页码越界为空页并标截断。
     *
     * <p>守护 {@code slice}/{@code hasMore}：按 {@code page × limit < total} 判会误报末页还有内容。
     */
    @Test
    void stageContentShouldPaginateAndMarkTruncation() {
        stubTask();
        stubArtifact(structureDoc(List.of(element("e-1", "TITLE", "一"), element("e-2", "PARAGRAPH", "二"),
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
        stubArtifact(structureDoc(List.of(page1, page3, page4)));

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
     * 解析条目：顺序号从 1 起、类型与展示文本逐列搬运。
     *
     * <p>守护 {@code parseItems} 的 seq 递增与字段搬运：顺序号错位会让两侧对齐失效。
     */
    @Test
    void parseItemsShouldCarrySeqTypeAndDisplay() {
        stubTask();
        ParseElement first = ParseElement.of("p-1", ElementType.PARAGRAPH);
        first.setText("第一段");
        ParseElement second = ParseElement.of("t-1", ElementType.TABLE);
        second.setText("表格");
        stubArtifact(parseDoc(List.of(first, second)));

        StageContentVO vo = service.stageContent(FILE_RESULT_ID, "PARSE", null, null, null, null);

        assertEquals(2, vo.getItems().size());
        assertEquals(1, vo.getItems().get(0).getSeq());
        assertEquals("p-1", vo.getItems().get(0).getAlignKey());
        assertEquals(ElementType.PARAGRAPH.name(), vo.getItems().get(0).getType());
        assertEquals("第一段", vo.getItems().get(0).getDisplay());
        assertEquals(2, vo.getItems().get(1).getSeq());
        assertEquals(ElementType.TABLE.name(), vo.getItems().get(1).getType());
    }

    /**
     * 预处理条目：展示/标准化文本与 extra 的各可选字段按非空并入，轨迹只下发非 KEEP 条目。
     *
     * <p>守护 {@code preprocessItems}/{@code nonKeepTrace}：把 KEEP 也下发会让响应体成倍膨胀。
     */
    @Test
    void preprocessItemsShouldCarryOptionalExtrasAndFilterKeepTrace() {
        stubTask();
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
        stubArtifact(preprocessView(List.of(rich, plain)));

        StageContentVO vo = service.stageContent(FILE_RESULT_ID, "PREPROCESS", null, null, null, null);

        var item = vo.getItems().getFirst();
        assertEquals("e-1", item.getAlignKey());
        assertEquals(1, item.getSeq());
        assertEquals("PARAGRAPH", item.getType());
        assertEquals("EXCLUDED_TOC", item.getStatus());
        assertEquals("展示", item.getDisplay());
        assertEquals("检索", item.getNormalized());
        Map<String, Object> extra = item.getExtra();
        assertEquals("原文", extra.get("rawText"));
        assertEquals(List.of("REPEATED_PAGE"), extra.get("marks"));
        assertTrue(String.valueOf(extra.get("normalizedFields")).contains("金额"));
        assertTrue(String.valueOf(extra.get("cells")).contains("单元格"));
        assertEquals(7, extra.get("page"));
        String trace = String.valueOf(extra.get("trace"));
        assertTrue(trace.contains("text-tidy-space"), trace);
        assertFalse(trace.contains("encoding-utf8"), trace);
        // 第二项没有任何可选字段：extra 为空且不下发轨迹
        assertEquals(2, vo.getItems().get(1).getSeq());
        assertTrue(vo.getItems().get(1).getExtra().isEmpty());
    }

    /**
     * 切片条目：分页结果逐条映射，顺序号取产物内 orderNo，对齐键取切片 ID（缺失回落顺序号）。
     *
     * <p>守护 {@code fillChunkPage}/{@code chunkItemOf}：对齐键与顺序号错位会让详情侧对不上片。
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
        when(chunkDbService.pageByChunkSetId(eq(60L), org.mockito.ArgumentMatchers.isNull(), anyLong(), anyLong()))
                .thenReturn(page);

        StageContentVO vo = service.stageContent(FILE_RESULT_ID, "CHUNK", null, null, 1, 2, null, null);

        assertTrue(vo.getLatest());
        assertEquals(2, vo.getTotal());
        assertEquals(2, vo.getItems().size());
        var item = vo.getItems().getFirst();
        assertEquals(7, item.getSeq());
        assertEquals("chunk-0007", item.getAlignKey());
        assertEquals("PARAGRAPH", item.getType());
        assertEquals("正文", item.getDisplay());
        assertEquals(7, item.getExtra().get("orderNo"));
        assertEquals("chunk-0001", item.getExtra().get("parentChunkId"));
        // 缺 chunkId 时对齐键回落为顺序号
        assertEquals(8, vo.getItems().get(1).getSeq());
        assertEquals("8", vo.getItems().get(1).getAlignKey());
    }

    /**
     * 切片集合缺失：保留空壳（不抛、不读切片表）。
     *
     * <p>守护 {@code fillChunkPage} 的空集合兜底。
     */
    @Test
    void chunkStageWithoutSetShouldReturnEmptyItems() {
        stubTask();
        stubProduct("art-chunk-missing");
        when(chunkSetDbService.getByArtifactId("art-chunk-missing")).thenReturn(null);

        StageContentVO vo = service.stageContent(FILE_RESULT_ID, "CHUNK", null, null, null, null, null, null);

        assertTrue(vo.getItems().isEmpty());
        assertEquals(0, vo.getTotal());
        verify(chunkDbService, org.mockito.Mockito.never())
                .pageByChunkSetId(anyLong(), any(), anyLong(), anyLong());
    }

    /**
     * 向量化条目：按记录顺序给顺序号，类型/状态/输入文本与专属字段逐列映射。
     *
     * <p>守护 {@code embedItemsBySet}/{@code itemOf}：顺序号与对齐键错位会让两侧无法互相定位。
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
        assertEquals("chunk-1", item.getExtra().get("chunkId"));
        assertEquals(9, item.getExtra().get("tokenCount"));
        assertEquals(Boolean.TRUE, item.getExtra().get("cacheHit"));
        assertEquals("req-1", item.getExtra().get("requestId"));
        assertEquals(2, vo.getItems().get(1).getSeq());
    }

    /**
     * 环节中文标签随任务解析下发（用于日志与提示）。
     *
     * <p>守护 {@code stageLabel}：标签恒为空串会让任务解析的提示失去环节信息。
     */
    @Test
    void stageContentShouldPassStageLabelToTaskResolution() {
        when(detailSupport.resolveTask(eq(FILE_RESULT_ID), eq(PipelineStage.PARSE), any(), any())).thenReturn(null);

        service.stageContent(FILE_RESULT_ID, "PARSE", TASK_ID, null, null, null);

        ArgumentCaptor<String> labelCaptor = ArgumentCaptor.forClass(String.class);
        verify(detailSupport).resolveTask(eq(FILE_RESULT_ID), eq(PipelineStage.PARSE), eq(TASK_ID), labelCaptor.capture());
        assertEquals(PipelineStage.PARSE.getLabel(), labelCaptor.getValue());
    }

    /** 任务与产物夹具（产物键固定为 art） */
    private void stubTask() {
        WbPipelineTask task = new WbPipelineTask();
        task.setId(TASK_ID);
        task.setFileResultId(FILE_RESULT_ID);
        task.setStatus("SUCCESS");
        task.setProductId(PRODUCT_ID);
        when(detailSupport.resolveTask(anyLong(), any(), any(), any())).thenReturn(task);
        stubProduct("art");
    }

    /** 产物行夹具：产物键由入参决定，返回产物行供调用方断言 */
    private WbPipelineProduct stubProduct(String artifactId) {
        WbPipelineProduct product = new WbPipelineProduct();
        product.setId(PRODUCT_ID);
        product.setArtifactId(artifactId);
        product.setStorageType(StorageType.MINIO.getCode());
        product.setStorageSourceId(SOURCE_ID);
        product.setBucket("artifacts");
        when(pipelineProductDbService.getById(PRODUCT_ID)).thenReturn(product);
        return product;
    }

    /** 产物内容夹具：按产物键 art 的对象位置写 JSON 字节 */
    private void stubArtifact(Object value) {
        WbPipelineProduct product = new WbPipelineProduct();
        product.setArtifactId("art");
        product.setStorageType(StorageType.MINIO.getCode());
        product.setStorageSourceId(SOURCE_ID);
        product.setBucket("artifacts");
        when(fileStorage.getObject(ObjectRef.ofProduct(product)))
                .thenReturn(JsonUtil.toJsonStr(value).getBytes(StandardCharsets.UTF_8));
    }

    /** 结构化产物夹具 */
    private UnifiedDocument structureDoc(List<UnifiedElement> elements) {
        UnifiedDocument document = new UnifiedDocument();
        document.setElements(elements);
        return document;
    }

    /** 解析产物夹具 */
    private ParseResult parseDoc(List<ParseElement> elements) {
        ParseSource source = new ParseSource();
        source.setSource("native");
        source.setProvider("test");
        source.setElements(elements);
        ParseResult result = new ParseResult();
        result.setSources(List.of(source));
        return result;
    }

    /** 预处理产物夹具 */
    private PreprocessView preprocessView(List<ViewElement> elements) {
        PreprocessView view = new PreprocessView();
        view.setElements(elements);
        return view;
    }

    /** 结构化元素夹具 */
    private UnifiedElement element(String id, String type, String text) {
        UnifiedElement element = new UnifiedElement();
        element.setId(id);
        element.setType(type);
        element.setText(text);
        return element;
    }

    /**
     * 文档页 1 是合法过滤值：回显为 1，而不是被归一成空。
     *
     * <p>守护 {@code stageContent} 的 {@code docPage < 1} 边界：写成小于等于会把第 1 页当未过滤。
     */
    @Test
    void stageContentWithDocPageOneShouldEchoDocPage() {
        stubTask();
        stubArtifact(parseDoc(List.of(ParseElement.of("p-1", ElementType.PARAGRAPH))));

        StageContentVO vo = service.stageContent(FILE_RESULT_ID, "PARSE", null, 1L, null, null);

        assertEquals(1, vo.getDocPage());
    }

    /**
     * 产物对象缺失：PARSE / STRUCTURE / PREPROCESS 三个环节都返回空内容而不抛错。
     *
     * <p>守护 {@code parseItems}/{@code structureItems}/{@code preprocessItems} 的产物缺失兜底：
     * 空集合换成异常或非空都会让页面在"产物被清理"时崩掉。
     */
    @Test
    void stageContentWithMissingArtifactShouldReturnEmptyItems() {
        stubTask();
        when(fileStorage.getObject(any())).thenReturn(null);

        for (String stage : List.of("PARSE", "STRUCTURE", "PREPROCESS")) {
            StageContentVO vo = service.stageContent(FILE_RESULT_ID, stage, null, null, null, null);
            assertTrue(vo.getItems().isEmpty(), stage);
            assertEquals(0, vo.getTotal(), stage);
            assertFalse(vo.getTruncated(), stage);
        }
    }

    /**
     * 向量化环节没有集合产物：返回空内容。
     *
     * <p>守护 {@code embedItemsBySet} 的空集合兜底：向量段未产出时页面应显示空而不是报错。
     */
    @Test
    void stageContentEmbedWithoutSetShouldReturnEmptyItems() {
        stubTask();
        stubArtifact(parseDoc(List.of(ParseElement.of("p-1", ElementType.PARAGRAPH))));
        when(embeddingSetDbService.getByArtifactId(any())).thenReturn(null);

        StageContentVO vo = service.stageContent(FILE_RESULT_ID, "EMBED", null, null, null, null);

        assertTrue(vo.getItems().isEmpty());
        assertEquals(0, vo.getTotal());
    }

}
