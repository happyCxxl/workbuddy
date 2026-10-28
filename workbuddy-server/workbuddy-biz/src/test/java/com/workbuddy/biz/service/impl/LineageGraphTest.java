package com.workbuddy.biz.service.impl;

import com.workbuddy.biz.service.db.WbChunkSetDbService;
import com.workbuddy.biz.service.db.WbEmbeddingSetDbService;
import com.workbuddy.biz.service.db.WbFileResultDbService;
import com.workbuddy.biz.service.db.WbKnowledgeBaseDbService;
import com.workbuddy.biz.service.db.WbPipelineProductDbService;
import com.workbuddy.biz.service.db.WbPipelineStepLogDbService;
import com.workbuddy.biz.service.db.WbPipelineTaskDbService;
import com.workbuddy.biz.service.support.access.FileResultAccessGuard;
import com.workbuddy.biz.testkit.SecurityTestSupport;
import com.workbuddy.common.domain.chunk.ChunkSet;
import com.workbuddy.common.domain.entity.WbChunkSet;
import com.workbuddy.common.domain.entity.WbEmbeddingSet;
import com.workbuddy.common.domain.entity.WbKnowledgeBase;
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.domain.entity.WbPipelineStepLog;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.dto.response.chunk.ChunkStatsVO;
import com.workbuddy.common.dto.response.embed.EmbedStatsVO;
import com.workbuddy.common.dto.response.lineage.LineageNodeVO;
import com.workbuddy.common.dto.response.lineage.LineageVO;
import com.workbuddy.common.dto.response.preprocess.PreprocessStatsVO;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.WorkbuddyException;
import com.workbuddy.filecenter.storage.FileStorage;
import com.workbuddy.worker.chunking.strategy.ChunkStrategyParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import com.workbuddy.biz.config.ConfigFixtures;
import static com.workbuddy.biz.service.impl.LineageQueryTestFixtures.FILE_RESULT_ID;
import static com.workbuddy.biz.service.impl.LineageQueryTestFixtures.KB_ID;
import static com.workbuddy.biz.service.impl.LineageQueryTestFixtures.chunkSetOfOneChunk;
import static com.workbuddy.biz.service.impl.LineageQueryTestFixtures.fileResultOfKb10;
import static com.workbuddy.biz.service.impl.LineageQueryTestFixtures.knowledgeBaseOf;
import static com.workbuddy.biz.service.impl.LineageQueryTestFixtures.product;
import static com.workbuddy.biz.service.impl.LineageQueryTestFixtures.stubBody;
import static com.workbuddy.biz.service.impl.LineageQueryTestFixtures.task;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 执行树图单测：节点顺序、血缘边、策略版本、产物引用、环节过滤、未完成任务与访问校验。
 *
 * <p>守护 {@code lineage} 的图结构语义：环节顺序、边反查、策略/能力字段与空文件口径。
 * 统计明细见 {@link LineageNodeStatsTest}，产物读取失败见 {@link LineageArtifactFailureTest}。
 */
@ExtendWith(MockitoExtension.class)
class LineageGraphTest {

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
        boundKnowledgeBase();
        FileResultAccessGuard accessGuard = new FileResultAccessGuard(fileResultDbService, knowledgeBaseDbService);
        service = new LineageQueryServiceImpl(pipelineTaskDbService, pipelineProductDbService, stepLogDbService,
                chunkSetDbService, embeddingSetDbService, fileStorage, accessGuard,
                new ChunkStrategyParser(ConfigFixtures.chunk()));
    }

    /** 让 10 号知识库归当前登录用户所有（归属校验要能过） */
    private void boundKnowledgeBase() {
        WbKnowledgeBase kb = knowledgeBaseOf(KB_ID);
        kb.setUserId(SecurityTestSupport.VIEWER_ID);
        lenient().when(knowledgeBaseDbService.getActiveById(KB_ID)).thenReturn(kb);
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
     * 多分支链路：节点按环节顺序、能力快照解析、统计与血缘边齐全。
     *
     * <p>守护 {@code lineage}：顺序/统计/边任一项错即失败。
     */
    @Test
    void shouldAssembleNodesEdgesAndStats() {
        WbChunkSet chunkSet1 = new WbChunkSet();
        chunkSet1.setArtifactId("art-c1");
        chunkSet1.setChunkCount(156);
        WbEmbeddingSet embedSet1 = new WbEmbeddingSet();
        embedSet1.setArtifactId("art-e1");
        embedSet1.setRecordCount(782);
        embedSet1.setCachedCount(12);
        // 多分支：parse→structure→{preproc1→chunk1→embed1, preproc2→chunk2}
        stubFile(List.of(
                task(20L, PipelineStage.PARSE.name(), null, 10L, null),
                task(51L, PipelineStage.STRUCTURE.name(), 10L, 20L, null),
                task(61L, PipelineStage.PREPROCESS.name(), 20L, 30L,
                        "{\"name\":\"preproc-default\",\"version\":\"v1\"}"),
                task(62L, PipelineStage.PREPROCESS.name(), 20L, 60L,
                        "{\"name\":\"preproc-strict\",\"version\":\"v2\"}"),
                task(71L, PipelineStage.CHUNK.name(), 30L, 40L,
                        "{\"name\":\"chunk-hybrid\",\"version\":\"v1\"}"),
                task(72L, PipelineStage.CHUNK.name(), 60L, 70L,
                        "{\"name\":\"chunk-hybrid\",\"version\":\"v1\"}"),
                task(81L, PipelineStage.EMBED.name(), 40L, 50L,
                        "{\"name\":\"embed-default\",\"version\":\"v1\"}")),
                List.of(
                        product(10L, PipelineStage.PARSE.name(), "art-p",
                                "{\"parserName\":\"pdfbox\",\"parserVersion\":\"3.0.4\"}"),
                        product(20L, PipelineStage.STRUCTURE.name(), "art-s",
                                "{\"parserName\":\"rules\",\"parserVersion\":\"v1\"}"),
                        product(30L, PipelineStage.PREPROCESS.name(), "art-p1", null),
                        product(60L, PipelineStage.PREPROCESS.name(), "art-p2", null),
                        product(40L, PipelineStage.CHUNK.name(), "art-c1", null),
                        product(70L, PipelineStage.CHUNK.name(), "art-c2", null),
                        product(50L, PipelineStage.EMBED.name(), "art-e1", null)),
                List.of(chunkSet1), List.of(embedSet1));
        WbPipelineStepLog step1 = new WbPipelineStepLog();
        step1.setTaskId(61L);
        step1.setMatchedCount(1200);
        step1.setChangedCount(64);
        when(stepLogDbService.listByTaskIds(List.of(61L, 62L))).thenReturn(List.of(step1));

        LineageVO vo = service.lineage(FILE_RESULT_ID);

        assertNotNull(vo.getNodes());
        assertEquals(FILE_RESULT_ID, vo.getFileResultId());
        assertEquals(7, vo.getNodes().size());
        // 环节顺序：PARSE, STRUCTURE, PREPROCESS×2, CHUNK×2, EMBED
        assertEquals("PARSE", vo.getNodes().get(0).getStage());
        assertEquals("STRUCTURE", vo.getNodes().get(1).getStage());
        assertEquals("PREPROCESS", vo.getNodes().get(2).getStage());
        assertEquals("PREPROCESS", vo.getNodes().get(3).getStage());
        assertEquals("CHUNK", vo.getNodes().get(4).getStage());
        assertEquals("CHUNK", vo.getNodes().get(5).getStage());
        assertEquals("EMBED", vo.getNodes().get(6).getStage());
        // 策略版本解析（有策略环节）
        assertEquals("preproc-default-v1", vo.getNodes().get(2).getStrategyVersion());
        assertEquals("preproc-strict-v2", vo.getNodes().get(3).getStrategyVersion());
        assertEquals("chunk-hybrid-v1", vo.getNodes().get(4).getStrategyVersion());
        // 产物 ID（前端「以此产物触发下游」传此值，非任务 ID）
        assertEquals(10L, vo.getNodes().get(0).getProductId());
        assertEquals(20L, vo.getNodes().get(1).getProductId());
        assertEquals(30L, vo.getNodes().get(2).getProductId());
        assertEquals(60L, vo.getNodes().get(3).getProductId());
        assertEquals(40L, vo.getNodes().get(4).getProductId());
        assertEquals(70L, vo.getNodes().get(5).getProductId());
        assertEquals(50L, vo.getNodes().get(6).getProductId());
        // 能力快照（无策略环节）：JSON 文本被解析成对象，而不是透传原文
        assertEquals("pdfbox", vo.getNodes().get(0).getCapability().getParserName());
        assertEquals("3.0.4", vo.getNodes().get(0).getCapability().getParserVersion());
        assertEquals("rules", vo.getNodes().get(1).getCapability().getParserName());
        assertEquals("v1", vo.getNodes().get(1).getCapability().getParserVersion());
        // 有策略环节不填能力快照
        assertNull(vo.getNodes().get(2).getCapability());
        // 统计摘要：计数按数字下发（前端要按数字格式化千分位）
        assertEquals(156, ((ChunkStatsVO) vo.getNodes().get(4).getStats()).getChunkCount());
        assertEquals(782, ((EmbedStatsVO) vo.getNodes().get(6).getStats()).getRecordCount());
        assertEquals(12, ((EmbedStatsVO) vo.getNodes().get(6).getStats()).getCachedCount());
        assertEquals(1200L, ((PreprocessStatsVO) vo.getNodes().get(2).getStats()).getMatched());
        assertEquals(64L, ((PreprocessStatsVO) vo.getNodes().get(2).getStats()).getChanged());
        // 血缘边：p→s, s→pr1, s→pr2, pr1→c1, pr2→c2, c1→e1 = 6 条
        assertEquals(6, vo.getEdges().size());
        assertEquals(20L, vo.getEdges().get(0).getFromTaskId());
        assertEquals(51L, vo.getEdges().get(0).getToTaskId());
    }

    /**
     * 无任务文件：节点与边都为空。
     *
     * <p>守护 {@code lineage}：空文件仍造节点即失败。
     */
    @Test
    void shouldReturnEmptyForFileWithoutTasks() {
        stubFile(List.of(), List.of());

        LineageVO vo = service.lineage(FILE_RESULT_ID);

        assertEquals(0, vo.getNodes().size());
        assertEquals(0, vo.getEdges().size());
    }

    /**
     * 上游产物不存在：节点可见但不建边。
     *
     * <p>守护 {@code lineage}：凭空建边即失败。
     */
    @Test
    void missingUpstreamProductShouldNotCreateEdge() {
        // chunk 任务指向不存在的上游产物 → 孤立节点、无对应边
        stubFile(List.of(task(71L, PipelineStage.CHUNK.name(), 999L, 40L,
                        "{\"name\":\"chunk-hybrid\",\"version\":\"v1\"}")),
                List.of(product(40L, PipelineStage.CHUNK.name(), "art-c1", null)));

        LineageVO vo = service.lineage(FILE_RESULT_ID);

        assertEquals(1, vo.getNodes().size());
        assertEquals(0, vo.getEdges().size());
    }

    /**
     * 切片快照损坏：策略版本与策略类口径留空，其余统计照常。
     *
     * <p>守护 {@code lineage}：损坏快照让整节点空白即失败。
     */
    @Test
    void chunkNodeWithCorruptStrategySnapshotShouldLeaveStrategyStatsEmpty() {
        // 同环节两个节点：71 快照可解析（策略类口径有值），72 快照损坏（策略类口径留空）
        stubFile(List.of(
                task(71L, PipelineStage.CHUNK.name(), null, 40L, "{\"name\":\"chunk-hybrid\",\"version\":\"v1\"}"),
                task(72L, PipelineStage.CHUNK.name(), null, 70L, "{oops")),
                List.of(product(40L, PipelineStage.CHUNK.name(), "art-c1", null),
                        product(70L, PipelineStage.CHUNK.name(), "art-c2", null)));
        ChunkSet body = chunkSetOfOneChunk();
        stubBody(fileStorage, "art-c1", body);
        stubBody(fileStorage, "art-c2", body);

        LineageVO vo = service.lineage(FILE_RESULT_ID);

        LineageNodeVO good = vo.getNodes().get(0);
        LineageNodeVO corrupt = vo.getNodes().get(1);
        // 快照可解析：策略版本与片长口径照常下发
        assertEquals("chunk-hybrid-v1", good.getStrategyVersion());
        assertNotNull(((ChunkStatsVO) good.getStats()).getTargetMaxLen());
        // 快照损坏：策略版本留空、策略类口径整组留空，其余统计照常（如实展示无策略快照）
        assertNull(corrupt.getStrategyVersion());
        assertEquals(1, ((ChunkStatsVO) corrupt.getStats()).getChunkCount());
        assertNull(((ChunkStatsVO) corrupt.getStats()).getTargetMaxLen());
        assertNull(((ChunkStatsVO) corrupt.getStats()).getMinMergeLen());
    }

    /**
     * 文件结果不存在按 40432 拒绝。
     *
     * <p>守护 {@code lineage}：空文件可查即失败。
     */
    @Test
    void missingFileResultShouldReject40432() {
        when(fileResultDbService.getById(FILE_RESULT_ID)).thenReturn(null);

        WorkbuddyException e = assertThrows(WorkbuddyException.class, () -> service.lineage(FILE_RESULT_ID));
        assertEquals(ErrorCode.FILE_RESULT_NOT_FOUND, e.getErrorCode());
    }

    /**
     * 越权文件结果按 40401 拒绝。
     *
     * <p>守护 {@code lineage}：越权可读即失败。
     */
    @Test
    void otherUserFileResultShouldReject40401() {
        when(fileResultDbService.getById(FILE_RESULT_ID)).thenReturn(fileResultOfKb10());
        // 归属看的是知识库归属：换一个登录用户，越权与"不存在"同样返回 40401
        SecurityTestSupport.loginOtherUser();

        WorkbuddyException e = assertThrows(WorkbuddyException.class, () -> service.lineage(FILE_RESULT_ID));
        assertEquals(ErrorCode.KB_NOT_FOUND, e.getErrorCode());
    }

    /**
     * 文件链条之外的环节不进血缘。
     *
     * <p>守护 {@code lineage} 的环节过滤：把 BUILD_INDEX 之类也算进文件链条会污染执行树。
     */
    @Test
    void nonChainTaskShouldBeExcluded() {
        stubFile(List.of(
                task(99L, PipelineStage.BUILD_INDEX.name(), null, null, null),
                task(90L, PipelineStage.PARSE.name(), null, 10L, null)), List.of());

        LineageVO vo = service.lineage(FILE_RESULT_ID);

        assertEquals(1, vo.getNodes().size());
        assertEquals(PipelineStage.PARSE.name(), vo.getNodes().getFirst().getStage());
    }

    /**
     * 策略快照：空串、缺 version、损坏一律不下发策略版本；完整快照拼 name-version。
     *
     * <p>守护 {@code resolveStrategyVersion} 的三处空值与解析兜底：拼出「name-」会误报策略。
     */
    @Test
    void strategyVersionShouldBeNullForBlankOrIncompleteSnapshot() {
        stubFile(List.of(
                task(71L, PipelineStage.CHUNK.name(), null, null, " "),
                task(72L, PipelineStage.CHUNK.name(), null, null, "{\"name\":\"chunk-hybrid\"}"),
                task(73L, PipelineStage.CHUNK.name(), null, null, "not-json"),
                task(74L, PipelineStage.CHUNK.name(), null, null,
                        "{\"name\":\"chunk-hybrid\",\"version\":\"v1\"}")), List.of());

        LineageVO vo = service.lineage(FILE_RESULT_ID);

        assertNull(vo.getNodes().get(0).getStrategyVersion());
        assertNull(vo.getNodes().get(1).getStrategyVersion());
        assertNull(vo.getNodes().get(2).getStrategyVersion());
        assertEquals("chunk-hybrid-v1", vo.getNodes().get(3).getStrategyVersion());
    }

    /**
     * 未完成（QUEUED / RUNNING）且尚无产物的任务同样是可见节点：节点=一次运行，
     * 卡片按状态显示"排队中/解析中"，统计与摘要留空。
     */
    @ParameterizedTest
    @EnumSource(value = PipelineTaskStatus.class, names = { "QUEUED", "RUNNING" })
    void pendingParseTaskWithoutProductShouldBeVisible(PipelineTaskStatus status) {
        WbPipelineTask parseTask = task(20L, PipelineStage.PARSE.name(), null, null, null);
        parseTask.setStatus(status.name());
        stubFile(List.of(parseTask), List.of());

        LineageVO vo = service.lineage(FILE_RESULT_ID);

        assertEquals(1, vo.getNodes().size(), status + ": 未完成任务必须在血缘里可见");
        LineageNodeVO node = vo.getNodes().get(0);
        assertEquals(status.name(), node.getStatus());
        assertEquals(PipelineStage.PARSE.name(), node.getStage());
        assertNull(node.getProductId());
        assertNull(node.getParseStats());
        assertNull(node.getParseSummary());
    }

    /**
     * 节点排序：按环节固定顺序，而不是按入参顺序。
     *
     * <p>守护 {@code lineage} 的比较器：返回 0 会让节点顺序随查询结果漂移。
     */
    @Test
    void lineageShouldOrderNodesByStageSequence() {
        WbPipelineTask embedTask = task(22L, PipelineStage.EMBED.name(), null, 10L, null);
        WbPipelineTask parseTask = task(20L, PipelineStage.PARSE.name(), null, 10L, null);
        // 入参故意倒序：EMBED 在前、PARSE 在后
        stubFile(List.of(embedTask, parseTask), List.of());

        List<LineageNodeVO> nodes = service.lineage(FILE_RESULT_ID).getNodes();

        assertEquals(PipelineStage.PARSE.name(), nodes.getFirst().getStage());
        assertEquals(20L, nodes.getFirst().getTaskId());
    }
}
