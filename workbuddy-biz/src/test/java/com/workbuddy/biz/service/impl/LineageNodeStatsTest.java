package com.workbuddy.biz.service.impl;

import com.workbuddy.biz.service.db.WbChunkSetDbService;
import com.workbuddy.biz.service.db.WbEmbeddingSetDbService;
import com.workbuddy.biz.service.db.WbFileResultDbService;
import com.workbuddy.biz.service.db.WbKnowledgeBaseDbService;
import com.workbuddy.biz.service.db.WbPipelineProductDbService;
import com.workbuddy.biz.service.db.WbPipelineStepLogDbService;
import com.workbuddy.biz.service.db.WbPipelineTaskDbService;
import com.workbuddy.biz.service.support.FileResultAccessGuard;
import com.workbuddy.biz.testkit.SecurityTestSupport;
import com.workbuddy.common.domain.entity.WbChunkSet;
import com.workbuddy.common.domain.entity.WbEmbeddingSet;
import com.workbuddy.common.domain.entity.WbKnowledgeBase;
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.domain.entity.WbPipelineStepLog;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.domain.input.FileReference;
import com.workbuddy.common.domain.parse.ParseResult;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.domain.parse.QualityInfo;
import com.workbuddy.common.dto.response.chunk.ChunkStatsVO;
import com.workbuddy.common.dto.response.embed.EmbedStatsVO;
import com.workbuddy.common.dto.response.lineage.LineageNodeVO;
import com.workbuddy.common.dto.response.lineage.LineageVO;
import com.workbuddy.common.dto.response.preprocess.PreprocessStatsVO;
import com.workbuddy.common.dto.response.structure.StructureStatsVO;
import com.workbuddy.common.enums.parse.ElementType;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.filecenter.storage.FileStorage;
import com.workbuddy.worker.chunking.ChunkProperties;
import com.workbuddy.worker.chunking.strategy.ChunkStrategyParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static com.workbuddy.biz.service.impl.LineageQueryTestFixtures.FILE_RESULT_ID;
import static com.workbuddy.biz.service.impl.LineageQueryTestFixtures.KB_ID;
import static com.workbuddy.biz.service.impl.LineageQueryTestFixtures.chunkSetOfOneChunk;
import static com.workbuddy.biz.service.impl.LineageQueryTestFixtures.documentOfOneElement;
import static com.workbuddy.biz.service.impl.LineageQueryTestFixtures.element;
import static com.workbuddy.biz.service.impl.LineageQueryTestFixtures.fileResultOfKb10;
import static com.workbuddy.biz.service.impl.LineageQueryTestFixtures.knowledgeBaseOf;
import static com.workbuddy.biz.service.impl.LineageQueryTestFixtures.parseResultOfOneParagraph;
import static com.workbuddy.biz.service.impl.LineageQueryTestFixtures.product;
import static com.workbuddy.biz.service.impl.LineageQueryTestFixtures.stubBody;
import static com.workbuddy.biz.service.impl.LineageQueryTestFixtures.task;
import static com.workbuddy.biz.service.impl.LineageQueryTestFixtures.viewOfKeptAndExcluded;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 执行树节点统计单测：解析/组装/预处理/切片/向量化的统计、摘要与策略类口径。
 *
 * <p>守护 {@code toNode} 的统计回填：漏搬产物字段、环节统计被通用 map 覆盖或串错口径都会让执行树失真。
 */
@ExtendWith(MockitoExtension.class)
class LineageNodeStatsTest {

    @Mock
    private WbFileResultDbService fileResultDbService;
    @Mock
    private WbPipelineTaskDbService pipelineTaskDbService;
    @Mock
    private WbPipelineProductDbService pipelineProductDbService;
    @Mock
    private WbPipelineStepLogDbService stepLogDbService;
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
        service = new LineageQueryServiceImpl(pipelineTaskDbService, pipelineProductDbService, stepLogDbService,
                chunkSetDbService, embeddingSetDbService, fileStorage, accessGuard,
                new ChunkStrategyParser(new ChunkProperties()));
    }

    /** 文件清单与集合行夹具（集合行为空） */
    private void stubFile(List<WbPipelineTask> tasks, List<WbPipelineProduct> products) {
        stubFile(tasks, products, List.of(), List.of());
    }

    /** 文件清单与集合行夹具 */
    private void stubFile(List<WbPipelineTask> tasks, List<WbPipelineProduct> products,
                          List<WbChunkSet> chunkSets, List<WbEmbeddingSet> embedSets) {
        when(fileResultDbService.getById(FILE_RESULT_ID)).thenReturn(fileResultOfKb10());
        when(pipelineTaskDbService.listByFileResultId(FILE_RESULT_ID)).thenReturn(tasks);
        when(pipelineProductDbService.listByFileResultId(FILE_RESULT_ID)).thenReturn(products);
        when(chunkSetDbService.listByFileResultId(FILE_RESULT_ID)).thenReturn(chunkSets);
        when(embeddingSetDbService.listByFileResultId(FILE_RESULT_ID)).thenReturn(embedSets);
    }

    /**
     * 解析节点：页数/元素/正文/表格/图片/页眉页脚与失败页统计逐项下发。
     *
     * <p>守护 {@code lineage}：统计口径错即失败。
     */
    @Test
    void parseNodeShouldCarryProductStats() {
        WbPipelineTask parseTask = task(20L, PipelineStage.PARSE.name(), null, 10L, null);
        parseTask.setStatus(PipelineTaskStatus.SUCCESS.name());
        parseTask.setStartedAt(LocalDateTime.of(2024, 5, 1, 10, 0, 0));
        parseTask.setFinishedAt(LocalDateTime.of(2024, 5, 1, 10, 0, 8));
        stubFile(List.of(parseTask), List.of(product(10L, PipelineStage.PARSE.name(), "art-p",
                "{\"parserName\":\"pdfbox\",\"parserVersion\":\"3.0.4\"}")));
        // 产物本体：2 段落 + 1 表格 + 2 图片 + 1 页眉 + 1 页脚 = 7 个元素；第 2 页未解析出内容
        ParseSource source = new ParseSource();
        source.setUnitCount(5);
        source.setElements(List.of(
                element(ElementType.PARAGRAPH), element(ElementType.PARAGRAPH),
                element(ElementType.TABLE), element(ElementType.IMAGE),
                element(ElementType.IMAGE), element(ElementType.HEADER),
                element(ElementType.FOOTER)));
        QualityInfo quality = new QualityInfo();
        quality.setFailedPages(List.of(2));
        ParseResult parseResult = new ParseResult();
        parseResult.setSources(List.of(source));
        parseResult.setQuality(quality);
        stubBody(fileStorage, "art-p", parseResult);

        LineageVO vo = service.lineage(FILE_RESULT_ID);

        LineageNodeVO node = vo.getNodes().get(0);
        assertEquals(5, node.getParseStats().getPageCount());
        assertEquals(7, node.getParseStats().getElementCount());
        assertEquals(2, node.getParseStats().getBodyCount());
        assertEquals(1, node.getParseStats().getTableCount());
        assertEquals(2, node.getParseStats().getImageCount());
        assertEquals(2, node.getParseStats().getHeaderFooterCount());
        assertEquals(1, node.getParseStats().getFailedUnitCount());
        assertEquals(2, node.getParseStats().getFailedFrom());
        assertEquals(2, node.getParseStats().getFailedTo());
        assertEquals(8000L, node.getParseStats().getDurationMs());
        assertEquals("1 单元未解析出内容（第 2–2）", node.getParseSummary());
    }

    /**
     * 解析节点：无判定单元数时页数取文件引用，异常为空。
     *
     * <p>守护 {@code lineage}：回退口径错即失败。
     */
    @Test
    void parseNodeWithoutArtifactShouldFallBackToPageCount() {
        WbPipelineTask parseTask = task(20L, PipelineStage.PARSE.name(), null, 10L, null);
        parseTask.setStatus(PipelineTaskStatus.SUCCESS.name());
        parseTask.setStartedAt(LocalDateTime.of(2024, 5, 1, 10, 0, 0));
        parseTask.setFinishedAt(LocalDateTime.of(2024, 5, 1, 10, 0, 8));
        stubFile(List.of(parseTask), List.of(product(10L, PipelineStage.PARSE.name(), "art-p",
                "{\"parserName\":\"pdfbox\",\"parserVersion\":\"3.0.4\"}")));
        // 产物本体：无判定单元数，页数取文件引用；无问题单元 → 成功态摘要为"无异常"
        ParseSource source = new ParseSource();
        source.setElements(List.of(element(ElementType.PARAGRAPH)));
        ParseResult parseResult = new ParseResult();
        parseResult.setSources(List.of(source));
        parseResult.setQuality(new QualityInfo());
        parseResult.setFile(new FileReference("f-1", "a.pdf", "sha", "application/pdf", 128));
        stubBody(fileStorage, "art-p", parseResult);

        LineageVO vo = service.lineage(FILE_RESULT_ID);

        LineageNodeVO node = vo.getNodes().get(0);
        assertEquals(128, node.getParseStats().getPageCount());
        assertEquals(1, node.getParseStats().getElementCount());
        assertNull(node.getParseStats().getFailedUnitCount());
        assertNull(node.getParseStats().getFailedFrom());
        assertNull(node.getParseStats().getFailedTo());
        assertEquals("无异常", node.getParseSummary());
    }

    /**
     * 五个环节齐备（任务号与环节顺序相反）：各节点统计、产物字段与摘要逐项下发。
     *
     * <p>守护 {@code toNode} 的产物统计与策略类口径：漏搬 artifactId/contentHash、
     * 组装/预处理统计被通用 map 覆盖、切片节点的上游视图口径缺失都会让执行树失真。
     */
    @Test
    void structurePreprocessAndChunkNodesShouldCarryArtifactStats() {
        WbPipelineTask parseTask = task(90L, PipelineStage.PARSE.name(), null, 10L, null);
        parseTask.setStatus(PipelineTaskStatus.FAILED.name());
        parseTask.setErrorCode("PARSE_TIMEOUT");
        parseTask.setErrorMsg("解析超时");
        parseTask.setStartedAt(LocalDateTime.of(2024, 5, 1, 10, 0, 0));
        parseTask.setFinishedAt(LocalDateTime.of(2024, 5, 1, 10, 0, 8));
        WbPipelineTask structureTask = task(10L, PipelineStage.STRUCTURE.name(), 10L, 20L, null);
        WbPipelineTask preprocessTask = task(50L, PipelineStage.PREPROCESS.name(), 20L, 30L,
                "{\"name\":\"preproc-default\",\"version\":\"v1\"}");
        WbPipelineTask chunkTask = task(40L, PipelineStage.CHUNK.name(), 30L, 40L,
                "{\"name\":\"chunk-hybrid\",\"version\":\"v1\","
                        + "\"routes\":{\"body\":{\"algorithm\":\"paragraph-aggregate\","
                        + "\"params\":{\"targetMaxLen\":200,\"softMaxLen\":200}}}}");
        WbPipelineTask embedTask = task(60L, PipelineStage.EMBED.name(), 40L, 50L,
                "{\"name\":\"embed-default\",\"version\":\"v1\"}");
        WbChunkSet chunkSetRow = new WbChunkSet();
        chunkSetRow.setArtifactId("art-c1");
        chunkSetRow.setChunkCount(5);
        WbEmbeddingSet embedSetRow = new WbEmbeddingSet();
        embedSetRow.setArtifactId("art-e1");
        embedSetRow.setRecordCount(7);
        embedSetRow.setCachedCount(2);
        stubFile(List.of(parseTask, structureTask, preprocessTask, chunkTask, embedTask), List.of(
                product(10L, PipelineStage.PARSE.name(), "art-p",
                        "{\"parserName\":\"pdfbox\",\"parserVersion\":\"3.0.4\","
                                + "\"ocr\":{\"model\":\"ocr-v1\",\"version\":\"1\"},"
                                + "\"layout\":{\"model\":\"layout-v2\",\"version\":\"2\"},"
                                + "\"table\":{\"model\":\"table-v3\",\"version\":\"3\"}}"),
                product(20L, PipelineStage.STRUCTURE.name(), "art-s", null),
                product(30L, PipelineStage.PREPROCESS.name(), "art-p1", null),
                product(40L, PipelineStage.CHUNK.name(), "art-c1", null),
                product(50L, PipelineStage.EMBED.name(), "art-e1", null)),
                List.of(chunkSetRow), List.of(embedSetRow));
        WbPipelineStepLog stepLog = new WbPipelineStepLog();
        stepLog.setTaskId(50L);
        stepLog.setMatchedCount(9);
        stepLog.setChangedCount(4);
        when(stepLogDbService.listByTaskIds(List.of(50L))).thenReturn(List.of(stepLog));
        stubBody(fileStorage, "art-p", parseResultOfOneParagraph());
        stubBody(fileStorage, "art-s", documentOfOneElement());
        stubBody(fileStorage, "art-p1", viewOfKeptAndExcluded());
        stubBody(fileStorage, "art-c1", chunkSetOfOneChunk());

        LineageVO vo = service.lineage(FILE_RESULT_ID);

        // 环节顺序优先于任务号：90 在 10 之前
        assertEquals(List.of(90L, 10L, 50L, 40L, 60L),
                vo.getNodes().stream().map(LineageNodeVO::getTaskId).toList());
        LineageNodeVO parse = vo.getNodes().get(0);
        assertEquals("PARSE_TIMEOUT", parse.getErrorCode());
        assertEquals("解析超时", parse.getErrorMsg());
        assertNotNull(parse.getStartedAt());
        assertNotNull(parse.getFinishedAt());
        assertEquals("art-p", parse.getArtifactId());
        assertEquals("art-p", parse.getContentHash());
        assertEquals("ocr-v1", parse.getCapability().getOcr().getModel());
        assertEquals("1", parse.getCapability().getOcr().getVersion());
        assertEquals("layout-v2", parse.getCapability().getLayout().getModel());
        assertEquals("2", parse.getCapability().getLayout().getVersion());
        assertEquals("table-v3", parse.getCapability().getTable().getModel());
        assertEquals("3", parse.getCapability().getTable().getVersion());
        assertNotNull(parse.getParseStats());
        assertNotNull(parse.getParseSummary());
        // 组装统计进通用 stats，摘要走 stageSummary
        LineageNodeVO structure = vo.getNodes().get(1);
        assertEquals("art-s", structure.getArtifactId());
        assertEquals(1, ((StructureStatsVO) structure.getStats()).getElementCount());
        assertNotNull(structure.getStageSummary());
        // 预处理统计并入通用 stats：step_log 的 matched/changed 与视图口径同时保留
        LineageNodeVO preprocess = vo.getNodes().get(2);
        assertEquals(9L, ((PreprocessStatsVO) preprocess.getStats()).getMatched());
        assertEquals(4L, ((PreprocessStatsVO) preprocess.getStats()).getChanged());
        assertEquals(2, ((PreprocessStatsVO) preprocess.getStats()).getElementCount());
        assertEquals(1, ((PreprocessStatsVO) preprocess.getStats()).getRetainedCount());
        assertNotNull(preprocess.getStageSummary());
        // 切片统计：产物现算覆盖集合表口径，且上游预处理视图给出进入/跳过口径
        LineageNodeVO chunk = vo.getNodes().get(3);
        assertEquals(1, ((ChunkStatsVO) chunk.getStats()).getChunkCount());
        assertEquals(200, ((ChunkStatsVO) chunk.getStats()).getTargetMaxLen());
        assertEquals(1, ((ChunkStatsVO) chunk.getStats()).getRoutedElementCount());
        assertEquals(1, ((ChunkStatsVO) chunk.getStats()).getSkippedElementCount());
        assertNotNull(chunk.getStageSummary());
        // 向量统计取集合表口径
        LineageNodeVO embed = vo.getNodes().get(4);
        assertEquals(7, ((EmbedStatsVO) embed.getStats()).getRecordCount());
        assertEquals(2, ((EmbedStatsVO) embed.getStats()).getCachedCount());
        // 四条上游边
        assertEquals(4, vo.getEdges().size());
    }
}
