package com.workbuddy.worker.chunking.impl;
import com.workbuddy.common.enums.chunk.ChunkContentType;
import com.workbuddy.common.enums.structure.UnifiedElementType;
import com.workbuddy.common.enums.preprocess.ViewElementStatus;

import com.workbuddy.common.domain.chunk.Chunk;
import com.workbuddy.common.domain.chunk.ChunkOutcome;
import com.workbuddy.common.domain.chunk.ChunkSet;
import com.workbuddy.common.domain.preprocess.PreprocessView;
import com.workbuddy.common.domain.preprocess.ViewCell;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.common.domain.structure.DocumentInfo;
import com.workbuddy.common.domain.structure.UnifiedDocument;
import com.workbuddy.common.domain.structure.UnifiedElement;
import com.workbuddy.common.enums.task.PipelineTaskErrorCode;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.worker.chunking.ChunkContext;
import com.workbuddy.worker.chunking.SliceContext;
import com.workbuddy.worker.chunking.slice.SliceStrategy;
import com.workbuddy.worker.chunking.impl.body.FixedWindowSliceStrategy;
import com.workbuddy.worker.chunking.impl.body.ParagraphSliceStrategy;
import com.workbuddy.worker.chunking.impl.body.SentenceAggregateSliceStrategy;
import com.workbuddy.worker.chunking.impl.body.StructureHybridSliceStrategy;
import com.workbuddy.worker.chunking.impl.body.TitleBoundarySliceStrategy;
import com.workbuddy.worker.chunking.impl.fallback.FixedWindowFallback;
import com.workbuddy.worker.chunking.impl.fallback.NoneFallback;
import com.workbuddy.worker.chunking.impl.fallback.RecursiveLengthFallback;
import com.workbuddy.worker.chunking.impl.image.ImageCaptionSliceStrategy;
import com.workbuddy.worker.chunking.impl.post.MinMergeProcessor;
import com.workbuddy.worker.chunking.impl.post.StructureOverlapProcessor;
import com.workbuddy.worker.chunking.impl.post.TitleInContentProcessor;
import com.workbuddy.worker.chunking.impl.table.TableContextMergedStrategy;
import com.workbuddy.worker.chunking.impl.table.TableRowGroupStrategy;
import com.workbuddy.worker.chunking.impl.table.TableRowSliceStrategy;
import com.workbuddy.worker.chunking.impl.table.TableWholeStrategy;
import com.workbuddy.common.enums.chunk.ChunkAlgorithm;
import com.workbuddy.worker.chunking.ChunkProperties;
import com.workbuddy.worker.chunking.strategy.ChunkStrategyParser;
import com.workbuddy.worker.testkit.StrategyFixtures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 切片管线单测：四路路由、父子层级、titlePath、剔除跳过、兜底降级、异常隔离、确定性、空集合；
 * 算法选择/flush 规则/tableInBodyFlow/leadParagraph/后置处理链/未支持算法报错。
 */
class ChunkPipelineTest {

    private ChunkPipeline pipeline() {
        ChunkProperties props = new ChunkProperties();
        return new ChunkPipeline(
                new SliceStrategyRegistry(List.of(
                        new ParagraphSliceStrategy(), new StructureHybridSliceStrategy(),
                        new TitleBoundarySliceStrategy(), new SentenceAggregateSliceStrategy(),
                        new FixedWindowSliceStrategy(),
                        new TableRowSliceStrategy(), new TableRowGroupStrategy(),
                        new TableWholeStrategy(), new TableContextMergedStrategy(),
                        new ImageCaptionSliceStrategy())),
                new FallbackSlicerRegistry(List.of(new RecursiveLengthFallback(),
                        new FixedWindowFallback(), new NoneFallback())),
                props,
                List.of(new StructureOverlapProcessor(), new MinMergeProcessor(), new TitleInContentProcessor()));
    }

    private ChunkPipeline pipelineWith(SliceStrategy... strategies) {
        ChunkProperties props = new ChunkProperties();
        List<SliceStrategy> all = new java.util.ArrayList<>(List.of(strategies));
        all.add(new ParagraphSliceStrategy());
        return new ChunkPipeline(
                new SliceStrategyRegistry(all),
                new FallbackSlicerRegistry(List.of(new RecursiveLengthFallback())),
                props,
                List.of(new StructureOverlapProcessor(), new MinMergeProcessor(), new TitleInContentProcessor()));
    }

    private ViewElement viewElement(String id, String type, Integer page, String text, String status) {
        ViewElement element = new ViewElement();
        element.setElementId(id);
        element.setType(type);
        element.setPage(page);
        element.setRawText(text);
        element.setDisplayText(text);
        element.setNormalizedText(text);
        element.setStatus(status);
        return element;
    }

    private UnifiedElement unifiedElement(String id, String type, Integer level, String caption) {
        UnifiedElement element = new UnifiedElement();
        element.setId(id);
        element.setType(type);
        element.setLevel(level);
        element.setCaption(caption);
        return element;
    }

    private ChunkContext context(PreprocessView view, UnifiedDocument document) {
        ChunkContext context = new ChunkContext();
        context.setView(view);
        context.setDocument(document);
        ChunkProperties props = new ChunkProperties();
        context.setStrategy(StrategyFixtures.chunkDefaults(props));
        context.setFileResultId(10L);
        context.setUpstreamProductRef(50L);
        return context;
    }

    /** 自定义策略（routes/pipeline 配置 JSON，经解析器补全默认） */
    private ChunkContext context(PreprocessView view, UnifiedDocument document, String configJson) {
        ChunkContext context = context(view, document);
        context.setStrategy(new ChunkStrategyParser(new ChunkProperties()).parse(configJson));
        return context;
    }

    private PreprocessView view(List<ViewElement> elements) {
        PreprocessView view = new PreprocessView();
        view.setDocumentId("doc-10");
        view.setElements(elements);
        return view;
    }

    private UnifiedDocument document(List<UnifiedElement> elements) {
        UnifiedDocument document = new UnifiedDocument();
        DocumentInfo info = new DocumentInfo();
        info.setDocumentId("doc-10");
        document.setDocumentInfo(info);
        document.setElements(elements);
        return document;
    }

    /**
     * 守护 {@code missingStrategyShouldFailWithChunkFailed}：该场景行为改动即失败。
     */
    @Test
    void missingStrategyShouldFailWithChunkFailed() {
        // 上下文无策略（快照缺失或损坏由 Runner 拦截）：管线自身同样拒绝，不构造默认策略
        List<ViewElement> elements = new ArrayList<>();
        elements.add(viewElement("n-1", UnifiedElementType.PARAGRAPH.name(), 1, "正文内容",
                ViewElementStatus.NORMAL.name()));
        ChunkContext context = context(view(elements), document(new ArrayList<>()));
        context.setStrategy(null);

        ChunkOutcome outcome = pipeline().chunk(context);

        assertEquals(PipelineTaskStatus.FAILED, outcome.getSuggestedStatus());
        assertEquals(PipelineTaskErrorCode.CHUNK_FAILED.name(), outcome.getErrorCode());
        assertEquals("任务未带切片策略快照，无法切片", outcome.getErrorMsg());
        assertNull(outcome.getChunkSet());
    }

    /**
     * 守护 {@code fullChainShouldProduceMixedChunksWithParentChildAndTitlePath}：该场景行为改动即失败。
     */
    @Test
    void fullChainShouldProduceMixedChunksWithParentChildAndTitlePath() {
        List<ViewElement> elements = new ArrayList<>();
        elements.add(viewElement("t1", UnifiedElementType.TITLE.name(), 1, "第一章 投标人须知", ViewElementStatus.NORMAL.name()));
        elements.add(viewElement("n-1", UnifiedElementType.PARAGRAPH.name(), 1, "投标保证金为人民币叁佰万元整。", ViewElementStatus.NORMAL.name()));
        elements.add(viewElement("n-2", UnifiedElementType.PARAGRAPH.name(), 1, "本须知适用于参与本次采购活动的所有供应商。", ViewElementStatus.NORMAL.name()));
        elements.add(viewElement("t2", UnifiedElementType.TITLE.name(), 2, "1.3 评分标准", ViewElementStatus.NORMAL.name()));
        elements.add(tableElement());
        elements.add(viewElement("i-1", UnifiedElementType.IMAGE.name(), 2, null, ViewElementStatus.NORMAL.name()));

        List<UnifiedElement> structures = new ArrayList<>();
        structures.add(unifiedElement("t1", UnifiedElementType.TITLE.name(), 1, null));
        structures.add(unifiedElement("t2", UnifiedElementType.TITLE.name(), 2, null));
        structures.add(tableStruct());
        structures.add(unifiedElement("i-1", UnifiedElementType.IMAGE.name(), null, "附件二：资质证书（扫描件）"));
        structures.add(unifiedElement("n-1", UnifiedElementType.PARAGRAPH.name(), null, null));
        structures.add(unifiedElement("n-2", UnifiedElementType.PARAGRAPH.name(), null, null));

        ChunkOutcome outcome = pipeline().chunk(context(view(elements), document(structures)));

        assertEquals(PipelineTaskStatus.SUCCESS, outcome.getSuggestedStatus());
        ChunkSet chunkSet = outcome.getChunkSet();
        assertEquals("cs-doc-10-chunk-hybrid-v1", chunkSet.getChunkSetId());
        assertEquals(6, outcome.getStepLogs().size());

        // 父子：两个章节各一个父片（SECTION）
        List<Chunk> parents = chunkSet.getChunks().stream()
                .filter(c -> ChunkContentType.SECTION.name().equals(c.getContentType())).toList();
        assertEquals(2, parents.size());
        assertEquals("第一章 投标人须知", parents.getFirst().getTitlePath());
        assertEquals("第一章 投标人须知 > 1.3 评分标准", parents.get(1).getTitlePath());

        // 正文子片挂父片
        Chunk bodyChild = chunkSet.getChunks().stream()
                .filter(c -> ChunkContentType.PARAGRAPH.name().equals(c.getContentType())).findFirst().orElse(null);
        assertNotNull(bodyChild);
        assertEquals(parents.getFirst().getChunkId(), bodyChild.getParentChunkId());
        assertEquals("第一章 投标人须知", bodyChild.getTitlePath());
        assertTrue(bodyChild.getContent().contains("投标保证金"));

        // 表格片：Markdown 表格形态（表头行 + 分隔行 + 数据行），评分项与分值同片；标题入正文默认开（标题链前缀）
        Chunk tableChunk = chunkSet.getChunks().stream()
                .filter(c -> ChunkContentType.TABLE.name().equals(c.getContentType())).findFirst().orElse(null);
        assertNotNull(tableChunk);
        assertEquals("第一章 投标人须知 > 1.3 评分标准\n| 评分项 | 分值 |\n|---|---|\n| A1 报价 | 30 |",
                tableChunk.getContent());
        assertEquals(parents.get(1).getChunkId(), tableChunk.getParentChunkId());
        assertEquals("t-1", tableChunk.getTableRef());
        assertTrue(tableChunk.getSourceElementIds().contains("tc-1"));

        // 图片片：图注占位
        Chunk imageChunk = chunkSet.getChunks().stream()
                .filter(c -> ChunkContentType.IMAGE.name().equals(c.getContentType())).findFirst().orElse(null);
        assertNotNull(imageChunk);
        assertTrue(imageChunk.getContent().contains("图注：附件二：资质证书（扫描件）"));

        // 编号与顺序
        assertEquals("chunk-0001", chunkSet.getChunks().getFirst().getChunkId());
        assertTrue(chunkSet.getChunks().stream().allMatch(c -> c.getTokenCount() > 0));
    }

    private UnifiedElement cell(String id, int row, int col, boolean header, String text) {
        UnifiedElement cell = unifiedElement(id, UnifiedElementType.TABLE_CELL.name(), null, null);
        cell.setRow(row);
        cell.setCol(col);
        cell.setIsHeader(header);
        cell.setText(text);
        return cell;
    }

    /** 表元素（2 列表格：表头 + 1 行数据） */
    private ViewElement tableElement() {
        ViewElement table = viewElement("t-1", UnifiedElementType.TABLE.name(), 2, null, ViewElementStatus.NORMAL.name());
        ViewCell h1 = new ViewCell();
        h1.setCellId("tc-h1");
        h1.setText("评分项");
        h1.setNormalizedText("评分项");
        ViewCell h2 = new ViewCell();
        h2.setCellId("tc-h2");
        h2.setText("分值");
        h2.setNormalizedText("分值");
        ViewCell c1 = new ViewCell();
        c1.setCellId("tc-1");
        c1.setText("A1 报价");
        c1.setNormalizedText("A1 报价");
        ViewCell c2 = new ViewCell();
        c2.setCellId("tc-2");
        c2.setText("30");
        c2.setNormalizedText("30");
        table.setCells(List.of(h1, h2, c1, c2));
        return table;
    }

    /** 结构表（与 tableElement 同构） */
    private UnifiedElement tableStruct() {
        UnifiedElement tableStruct = unifiedElement("t-1", UnifiedElementType.TABLE.name(), null, null);
        tableStruct.setCells(List.of(
                cell("tc-h1", 0, 0, true, "评分项"),
                cell("tc-h2", 0, 1, true, "分值"),
                cell("tc-1", 1, 0, false, "A1 报价"),
                cell("tc-2", 1, 1, false, "30")));
        tableStruct.setPage(2);
        return tableStruct;
    }

    /**
     * 守护 {@code tableShouldBreakBodyGroupAndKeepDocumentOrder}：该场景行为改动即失败。
     */
    @Test
    void tableShouldBreakBodyGroupAndKeepDocumentOrder() {
        List<ViewElement> elements = new ArrayList<>();
        elements.add(viewElement("n-1", UnifiedElementType.PARAGRAPH.name(), 1, "表格前段落", ViewElementStatus.NORMAL.name()));
        elements.add(tableElement());
        elements.add(viewElement("n-2", UnifiedElementType.PARAGRAPH.name(), 3, "表格后段落", ViewElementStatus.NORMAL.name()));
        List<UnifiedElement> structures = new ArrayList<>(List.of(tableStruct()));

        ChunkOutcome outcome = pipeline().chunk(context(view(elements), document(structures)));

        // 表格打断正文聚合：三段各成一片、顺序符合文档顺序
        List<Chunk> chunks = outcome.getChunkSet().getChunks();
        assertEquals(3, chunks.size());
        assertEquals(ChunkContentType.PARAGRAPH.name(), chunks.getFirst().getContentType());
        assertEquals("表格前段落", chunks.getFirst().getContent());
        assertEquals(List.of(1), chunks.getFirst().getPageRange());
        assertEquals(ChunkContentType.TABLE.name(), chunks.get(1).getContentType());
        assertEquals(ChunkContentType.PARAGRAPH.name(), chunks.get(2).getContentType());
        assertEquals("表格后段落", chunks.get(2).getContent());
        assertEquals(List.of(3), chunks.get(2).getPageRange());
        assertTrue(chunks.stream().noneMatch(c -> c.getContent().contains("表格前段落\n表格后段落")));
    }

    /**
     * 守护 {@code excludedAndRepeatedShouldBeSkipped}：该场景行为改动即失败。
     */
    @Test
    void excludedAndRepeatedShouldBeSkipped() {
        List<ViewElement> elements = new ArrayList<>();
        elements.add(viewElement("h-1", UnifiedElementType.HEADER.name(), 1, "页眉文本", ViewElementStatus.EXCLUDED_HEADER.name()));
        elements.add(viewElement("n-1", UnifiedElementType.PARAGRAPH.name(), 1, "重复段落", ViewElementStatus.REPEATED.name()));
        elements.add(viewElement("n-2", UnifiedElementType.PARAGRAPH.name(), 1, "正常段落内容", ViewElementStatus.NORMAL.name()));

        ChunkOutcome outcome = pipeline().chunk(context(view(elements), document(new ArrayList<>())));

        assertEquals(1, outcome.getChunkSet().getChunkCount());
        assertEquals("正常段落内容", outcome.getChunkSet().getChunks().getFirst().getContent());
    }

    /**
     * 守护 {@code overlongParagraphShouldFallbackRecursively}：该场景行为改动即失败。
     */
    @Test
    void overlongParagraphShouldFallbackRecursively() {
        List<ViewElement> elements = new ArrayList<>();
        elements.add(viewElement("n-1", UnifiedElementType.PARAGRAPH.name(), 1,
                "a".repeat(1200), ViewElementStatus.NORMAL.name()));

        ChunkOutcome outcome = pipeline().chunk(context(view(elements), document(new ArrayList<>())));

        List<Chunk> fallbacks = outcome.getChunkSet().getChunks().stream()
                .filter(c -> ChunkContentType.FALLBACK.name().equals(c.getContentType())).toList();
        assertEquals(3, fallbacks.size());
        assertTrue(fallbacks.stream().allMatch(c -> "超长段落递归降级".equals(c.getFallbackReason())));
        assertTrue(fallbacks.stream().allMatch(c -> c.getCharCount() <= 500));
    }

    /**
     * 守护 {@code titlePathShouldCapAtThreeLevels}：该场景行为改动即失败。
     */
    @Test
    void titlePathShouldCapAtThreeLevels() {
        List<ViewElement> elements = new ArrayList<>();
        List<UnifiedElement> structures = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            elements.add(viewElement("t" + i, UnifiedElementType.TITLE.name(), 1, "标题" + i, ViewElementStatus.NORMAL.name()));
            structures.add(unifiedElement("t" + i, UnifiedElementType.TITLE.name(), i, null));
            elements.add(viewElement("p" + i, UnifiedElementType.PARAGRAPH.name(), 1, "段落" + i, ViewElementStatus.NORMAL.name()));
            structures.add(unifiedElement("p" + i, UnifiedElementType.PARAGRAPH.name(), null, null));
        }

        ChunkOutcome outcome = pipeline().chunk(context(view(elements), document(structures)));

        Chunk last = outcome.getChunkSet().getChunks().getLast();
        assertEquals("标题2 > 标题3 > 标题4", last.getTitlePath());
    }

    /**
     * 守护 {@code emptyViewShouldFailWithChunkEmpty}：该场景行为改动即失败。
     */
    @Test
    void emptyViewShouldFailWithChunkEmpty() {
        ChunkOutcome outcome = pipeline().chunk(context(view(new ArrayList<>()), document(new ArrayList<>())));

        assertEquals(PipelineTaskStatus.FAILED, outcome.getSuggestedStatus());
        assertEquals(PipelineTaskErrorCode.CHUNK_EMPTY.name(), outcome.getErrorCode());
    }

    /**
     * 守护 {@code allExcludedShouldFailWithChunkEmpty}：该场景行为改动即失败。
     */
    @Test
    void allExcludedShouldFailWithChunkEmpty() {
        List<ViewElement> elements = new ArrayList<>();
        elements.add(viewElement("h-1", UnifiedElementType.HEADER.name(), 1, "页眉", ViewElementStatus.EXCLUDED_HEADER.name()));

        ChunkOutcome outcome = pipeline().chunk(context(view(elements), document(new ArrayList<>())));

        assertEquals(PipelineTaskStatus.FAILED, outcome.getSuggestedStatus());
        assertEquals(PipelineTaskErrorCode.CHUNK_EMPTY.name(), outcome.getErrorCode());
    }

    /**
     * 守护 {@code slicerExceptionShouldIsolateToPartialSuccess}：该场景行为改动即失败。
     */
    @Test
    void slicerExceptionShouldIsolateToPartialSuccess() {
        ChunkPipeline pipeline = pipelineWith(new BrokenTableStrategy());
        List<ViewElement> elements = new ArrayList<>();
        elements.add(viewElement("t-1", UnifiedElementType.TABLE.name(), 1, null, ViewElementStatus.NORMAL.name()));
        elements.add(viewElement("n-1", UnifiedElementType.PARAGRAPH.name(), 1, "正文照常切片", ViewElementStatus.NORMAL.name()));

        ChunkOutcome outcome = pipeline.chunk(context(view(elements), document(new ArrayList<>())));

        assertEquals(PipelineTaskStatus.PARTIAL_SUCCESS, outcome.getSuggestedStatus());
        assertTrue(outcome.getWarnings().stream().anyMatch(w -> w.contains("TABLE")));
        // 正文片照产（异常只影响该切片器产物）
        assertEquals(1, outcome.getChunkSet().getChunkCount());
        assertEquals(ChunkContentType.PARAGRAPH.name(), outcome.getChunkSet().getChunks().getFirst().getContentType());
    }

    /**
     * 守护 {@code sameInputSameStrategyShouldBeDeterministic}：该场景行为改动即失败。
     */
    @Test
    void sameInputSameStrategyShouldBeDeterministic() {
        List<ViewElement> elements = new ArrayList<>();
        elements.add(viewElement("t1", UnifiedElementType.TITLE.name(), 1, "第一章", ViewElementStatus.NORMAL.name()));
        elements.add(viewElement("n-1", UnifiedElementType.PARAGRAPH.name(), 1, "正文内容", ViewElementStatus.NORMAL.name()));
        List<UnifiedElement> structures = new ArrayList<>();
        structures.add(unifiedElement("t1", UnifiedElementType.TITLE.name(), 1, null));
        structures.add(unifiedElement("n-1", UnifiedElementType.PARAGRAPH.name(), null, null));

        ChunkOutcome first = pipeline().chunk(context(view(elements), document(structures)));
        ChunkOutcome second = pipeline().chunk(context(view(elements), document(structures)));

        assertEquals(first.getChunkSet().getChunkSetId(), second.getChunkSet().getChunkSetId());
        assertEquals(first.getChunkSet().getChunkCount(), second.getChunkSet().getChunkCount());
        assertEquals(first.getChunkSet().getChunks().getFirst().getContent(), second.getChunkSet().getChunks().getFirst().getContent());
    }

    // ---------------- 扩展用例 ----------------

    /**
     * 守护 {@code tableInBodyFlowShouldMergeTableIntoBodyChunk}：该场景行为改动即失败。
     */
    @Test
    void tableInBodyFlowShouldMergeTableIntoBodyChunk() {
        List<ViewElement> elements = new ArrayList<>();
        elements.add(viewElement("n-1", UnifiedElementType.PARAGRAPH.name(), 1, "表格前段落", ViewElementStatus.NORMAL.name()));
        elements.add(tableElement());
        elements.add(viewElement("n-2", UnifiedElementType.PARAGRAPH.name(), 3, "表格后段落", ViewElementStatus.NORMAL.name()));
        List<UnifiedElement> structures = new ArrayList<>(List.of(tableStruct()));

        ChunkOutcome outcome = pipeline().chunk(context(view(elements), document(structures),
                "{\"pipeline\":{\"tableInBodyFlow\":\"ON\"}}"));

        List<Chunk> chunks = outcome.getChunkSet().getChunks();
        assertEquals(1, chunks.size());
        Chunk chunk = chunks.getFirst();
        assertEquals(ChunkContentType.PARAGRAPH.name(), chunk.getContentType());
        assertTrue(chunk.getContent().contains("表格前段落"));
        assertTrue(chunk.getContent().contains("| 评分项 | 分值 |"));
        assertTrue(chunk.getContent().contains("表格后段落"));
    }

    /**
     * 守护 {@code contextMergedTableShouldCarryLeadParagraph}：该场景行为改动即失败。
     */
    @Test
    void contextMergedTableShouldCarryLeadParagraph() {
        List<ViewElement> elements = new ArrayList<>();
        elements.add(viewElement("n-1", UnifiedElementType.PARAGRAPH.name(), 1, "引出表格的说明段落", ViewElementStatus.NORMAL.name()));
        elements.add(tableElement());
        List<UnifiedElement> structures = new ArrayList<>(List.of(tableStruct()));

        ChunkOutcome outcome = pipeline().chunk(context(view(elements), document(structures),
                "{\"routes\":{\"table\":{\"algorithm\":\"context-merged\"}}}"));

        List<Chunk> tables = outcome.getChunkSet().getChunks().stream()
                .filter(c -> ChunkContentType.TABLE.name().equals(c.getContentType())).toList();
        assertEquals(1, tables.size());
        assertTrue(tables.getFirst().getContent().startsWith("引出表格的说明段落\n| 评分项 | 分值 |"));
    }

    /**
     * 守护 {@code titleInContentOffShouldKeepContentClean}：该场景行为改动即失败。
     */
    @Test
    void titleInContentOffShouldKeepContentClean() {
        List<ViewElement> elements = new ArrayList<>();
        elements.add(viewElement("t1", UnifiedElementType.TITLE.name(), 1, "第一章 投标人须知", ViewElementStatus.NORMAL.name()));
        elements.add(tableElement());
        List<UnifiedElement> structures = new ArrayList<>();
        structures.add(unifiedElement("t1", UnifiedElementType.TITLE.name(), 1, null));
        structures.add(tableStruct());

        ChunkOutcome outcome = pipeline().chunk(context(view(elements), document(structures),
                "{\"pipeline\":{\"titleInContent\":\"OFF\"}}"));

        Chunk tableChunk = outcome.getChunkSet().getChunks().stream()
                .filter(c -> ChunkContentType.TABLE.name().equals(c.getContentType())).findFirst().orElse(null);
        assertNotNull(tableChunk);
        assertTrue(tableChunk.getContent().startsWith("| 评分项 | 分值 |"));
        assertEquals("第一章 投标人须知", tableChunk.getTitlePath());
    }

    /**
     * 守护 {@code structureOverlapShouldPrefixAdjacentChunks}：该场景行为改动即失败。
     */
    @Test
    void structureOverlapShouldPrefixAdjacentChunks() {
        String longText = "a".repeat(900);
        List<ViewElement> elements = new ArrayList<>();
        elements.add(viewElement("n-1", UnifiedElementType.PARAGRAPH.name(), 1, longText, ViewElementStatus.NORMAL.name()));
        elements.add(viewElement("n-2", UnifiedElementType.PARAGRAPH.name(), 1, "b".repeat(500), ViewElementStatus.NORMAL.name()));

        ChunkOutcome outcome = pipeline().chunk(context(view(elements), document(new ArrayList<>()),
                "{\"pipeline\":{\"structureOverlap\":\"50\",\"titleInContent\":\"OFF\"}}"));

        List<Chunk> chunks = outcome.getChunkSet().getChunks();
        assertEquals(2, chunks.size());
        String first = chunks.getFirst().getContent();
        assertTrue(chunks.get(1).getContent().startsWith(first.substring(first.length() - 50) + "\n"));
    }

    /**
     * 守护 {@code minMergeShouldMergeAdjacentBodyFragment}：该场景行为改动即失败。
     */
    @Test
    void minMergeShouldMergeAdjacentBodyFragment() {
        List<ViewElement> elements = new ArrayList<>();
        elements.add(viewElement("n-1", UnifiedElementType.PARAGRAPH.name(), 1, "a".repeat(900), ViewElementStatus.NORMAL.name()));
        elements.add(viewElement("n-2", UnifiedElementType.PARAGRAPH.name(), 2, "碎片", ViewElementStatus.NORMAL.name()));

        ChunkOutcome outcome = pipeline().chunk(context(view(elements), document(new ArrayList<>()),
                "{\"pipeline\":{\"minMergeLen\":\"300\",\"titleInContent\":\"OFF\"}}"));

        List<Chunk> chunks = outcome.getChunkSet().getChunks();
        assertEquals(1, chunks.size());
        assertTrue(chunks.getFirst().getContent().endsWith("碎片"));
        assertEquals(903, chunks.getFirst().getCharCount());
    }

    /**
     * 守护 {@code noneFallbackShouldKeepOverlongSingleChunk}：该场景行为改动即失败。
     */
    @Test
    void noneFallbackShouldKeepOverlongSingleChunk() {
        List<ViewElement> elements = new ArrayList<>();
        elements.add(viewElement("n-1", UnifiedElementType.PARAGRAPH.name(), 1, "a".repeat(1200), ViewElementStatus.NORMAL.name()));

        ChunkOutcome outcome = pipeline().chunk(context(view(elements), document(new ArrayList<>()),
                "{\"routes\":{\"fallback\":{\"algorithm\":\"none\"}}}"));

        List<Chunk> chunks = outcome.getChunkSet().getChunks();
        assertEquals(1, chunks.size());
        assertEquals(ChunkContentType.FALLBACK.name(), chunks.getFirst().getContentType());
        assertEquals(1200, chunks.getFirst().getCharCount());
    }

    /**
     * 守护 {@code unsupportedAlgorithmShouldFailClearly}：该场景行为改动即失败。
     */
    @Test
    void unsupportedAlgorithmShouldFailClearly() {
        List<ViewElement> elements = new ArrayList<>();
        elements.add(viewElement("n-1", UnifiedElementType.PARAGRAPH.name(), 1, "正文内容", ViewElementStatus.NORMAL.name()));

        assertThrows(KnowledgeException.class, () -> pipeline().chunk(
                context(view(elements), document(new ArrayList<>()),
                        "{\"routes\":{\"body\":{\"algorithm\":\"semantic\"}}}")));
    }

    /** 必抛异常的表格切片器（模拟切片器缺陷：只影响该类型产物） */
    private static final class BrokenTableStrategy implements SliceStrategy {
        @Override
        public ChunkAlgorithm algorithm() {
            return ChunkAlgorithm.TABLE_ROW_SLICE;
        }

        @Override
        public List<Chunk> slice(ViewElement element, SliceContext context) {
            throw new IllegalStateException("boom");
        }
    }
}
