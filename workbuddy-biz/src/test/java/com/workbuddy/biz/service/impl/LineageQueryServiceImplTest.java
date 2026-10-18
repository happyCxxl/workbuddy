package com.workbuddy.biz.service.impl;

import com.workbuddy.biz.service.db.WbChunkSetDbService;
import com.workbuddy.biz.service.db.WbEmbeddingSetDbService;
import com.workbuddy.biz.service.db.WbKnowledgeBaseDbService;
import com.workbuddy.biz.service.db.WbFileResultDbService;
import com.workbuddy.biz.service.db.WbPipelineProductDbService;
import com.workbuddy.biz.service.db.WbPipelineStepLogDbService;
import com.workbuddy.biz.service.db.WbPipelineTaskDbService;
import com.workbuddy.biz.testkit.SecurityTestSupport;
import com.workbuddy.common.domain.chunk.Chunk;
import com.workbuddy.common.domain.chunk.ChunkSet;
import com.workbuddy.common.domain.entity.WbKnowledgeBase;
import com.workbuddy.common.domain.entity.WbChunkSet;
import com.workbuddy.common.domain.entity.WbEmbeddingSet;
import com.workbuddy.common.domain.entity.WbFileResult;
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.domain.entity.WbPipelineStepLog;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.domain.input.FileReference;
import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseResult;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.domain.parse.QualityInfo;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.dto.response.lineage.LineageNodeVO;
import com.workbuddy.common.dto.response.lineage.LineageVO;
import com.workbuddy.common.enums.parse.ElementType;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.biz.service.support.FileResultAccessGuard;
import com.workbuddy.filecenter.storage.FileStorage;
import com.workbuddy.worker.chunking.ChunkProperties;
import com.workbuddy.worker.chunking.strategy.ChunkStrategyParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 执行树聚合单测：多分支节点/血缘边/统计摘要/解析环节统计/产物不可读/未完成任务的节点可见性/孤立节点/空文件/40432。
 */
@ExtendWith(MockitoExtension.class)
class LineageQueryServiceImplTest {

    /** 产物所在的数据源 */
    private static final Long SOURCE_ID = 1L;

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
        boundKnowledgeBase(10L);
        FileResultAccessGuard accessGuard = new FileResultAccessGuard(fileResultDbService, knowledgeBaseDbService);
        service = new LineageQueryServiceImpl(pipelineTaskDbService,
                pipelineProductDbService, stepLogDbService, chunkSetDbService, embeddingSetDbService,
                fileStorage, accessGuard, new ChunkStrategyParser(new ChunkProperties()));
    }

    private WbPipelineTask task(Long id, String stage, Long upstreamProductId, Long productId, String snapshot) {
        WbPipelineTask task = new WbPipelineTask();
        task.setId(id);
        task.setFileResultId(10L);
        task.setStage(stage);
        task.setUpstreamProductId(upstreamProductId);
        task.setProductId(productId);
        task.setStrategySnapshot(snapshot);
        task.setStatus(PipelineTaskStatus.SUCCESS.name());
        return task;
    }

    private WbPipelineProduct product(Long id, String stage, String artifactId, String capability) {
        WbPipelineProduct product = new WbPipelineProduct();
        product.setId(id);
        product.setFileResultId(10L);
        product.setStage(stage);
        product.setArtifactId(artifactId);
        product.setContentHash(artifactId);
        product.setCapabilitySnapshot(capability);
        // 读取按记录里的数据源与桶名定位：行对象必须带上这两段
        product.setStorageType(StorageType.MINIO.getCode());
        product.setStorageSourceId(SOURCE_ID);
        product.setBucket("artifacts");
        return product;
    }

    @Test
    void shouldAssembleNodesEdgesAndStats() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResultOfKb10());
        // 多分支：parse→structure→{preproc1→chunk1→embed1, preproc2→chunk2}
        when(pipelineTaskDbService.listByFileResultId(10L)).thenReturn(List.of(
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
                        "{\"name\":\"embed-default\",\"version\":\"v1\"}")));
        when(pipelineProductDbService.listByFileResultId(10L)).thenReturn(List.of(
                product(10L, PipelineStage.PARSE.name(), "art-p",
                        "{\"parserName\":\"pdfbox\",\"parserVersion\":\"3.0.4\"}"),
                product(20L, PipelineStage.STRUCTURE.name(), "art-s",
                        "{\"parserName\":\"rules\",\"parserVersion\":\"v1\"}"),
                product(30L, PipelineStage.PREPROCESS.name(), "art-p1", null),
                product(60L, PipelineStage.PREPROCESS.name(), "art-p2", null),
                product(40L, PipelineStage.CHUNK.name(), "art-c1", null),
                product(70L, PipelineStage.CHUNK.name(), "art-c2", null),
                product(50L, PipelineStage.EMBED.name(), "art-e1", null)));
        WbChunkSet chunkSet1 = new WbChunkSet();
        chunkSet1.setArtifactId("art-c1");
        chunkSet1.setChunkCount(156);
        when(chunkSetDbService.listByFileResultId(10L)).thenReturn(List.of(chunkSet1));
        WbEmbeddingSet embedSet1 = new WbEmbeddingSet();
        embedSet1.setArtifactId("art-e1");
        embedSet1.setRecordCount(782);
        embedSet1.setCachedCount(12);
        when(embeddingSetDbService.listByFileResultId(10L)).thenReturn(List.of(embedSet1));
        WbPipelineStepLog step1 = new WbPipelineStepLog();
        step1.setTaskId(61L);
        step1.setMatchedCount(1200);
        step1.setChangedCount(64);
        when(stepLogDbService.listByTaskIds(List.of(61L, 62L))).thenReturn(List.of(step1));

        LineageVO vo = service.lineage(10L);

        assertNotNull(vo.getNodes());
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
        assertEquals(156, vo.getNodes().get(4).getStats().get("chunkCount"));
        assertEquals(782, vo.getNodes().get(6).getStats().get("recordCount"));
        assertEquals(12, vo.getNodes().get(6).getStats().get("cachedCount"));
        assertEquals(1200L, vo.getNodes().get(2).getStats().get("matched"));
        assertEquals(64L, vo.getNodes().get(2).getStats().get("changed"));
        // 血缘边：p→s, s→pr1, s→pr2, pr1→c1, pr2→c2, c1→e1 = 6 条
        assertEquals(6, vo.getEdges().size());
        assertEquals(20L, vo.getEdges().get(0).getFromTaskId());
        assertEquals(51L, vo.getEdges().get(0).getToTaskId());
    }

    @Test
    void shouldReturnEmptyForFileWithoutTasks() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResultOfKb10());
        when(pipelineTaskDbService.listByFileResultId(10L)).thenReturn(List.of());
        when(pipelineProductDbService.listByFileResultId(10L)).thenReturn(List.of());
        when(chunkSetDbService.listByFileResultId(10L)).thenReturn(List.of());
        when(embeddingSetDbService.listByFileResultId(10L)).thenReturn(List.of());

        LineageVO vo = service.lineage(10L);

        assertEquals(0, vo.getNodes().size());
        assertEquals(0, vo.getEdges().size());
    }

    @Test
    void missingUpstreamProductShouldNotCreateEdge() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResultOfKb10());
        // chunk 任务指向不存在的上游产物 → 孤立节点、无对应边
        when(pipelineTaskDbService.listByFileResultId(10L)).thenReturn(List.of(
                task(71L, PipelineStage.CHUNK.name(), 999L, 40L,
                        "{\"name\":\"chunk-hybrid\",\"version\":\"v1\"}")));
        when(pipelineProductDbService.listByFileResultId(10L)).thenReturn(List.of(
                product(40L, PipelineStage.CHUNK.name(), "art-c1", null)));
        when(chunkSetDbService.listByFileResultId(10L)).thenReturn(List.of());
        when(embeddingSetDbService.listByFileResultId(10L)).thenReturn(List.of());

        LineageVO vo = service.lineage(10L);

        assertEquals(1, vo.getNodes().size());
        assertEquals(0, vo.getEdges().size());
    }

    @Test
    void chunkNodeWithCorruptStrategySnapshotShouldLeaveStrategyStatsEmpty() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResultOfKb10());
        // 同环节两个节点：71 快照可解析（策略类口径有值），72 快照损坏（策略类口径留空）
        when(pipelineTaskDbService.listByFileResultId(10L)).thenReturn(List.of(
                task(71L, PipelineStage.CHUNK.name(), null, 40L, "{\"name\":\"chunk-hybrid\",\"version\":\"v1\"}"),
                task(72L, PipelineStage.CHUNK.name(), null, 70L, "{oops")));
        when(pipelineProductDbService.listByFileResultId(10L)).thenReturn(List.of(
                product(40L, PipelineStage.CHUNK.name(), "art-c1", null),
                product(70L, PipelineStage.CHUNK.name(), "art-c2", null)));
        when(chunkSetDbService.listByFileResultId(10L)).thenReturn(List.of());
        when(embeddingSetDbService.listByFileResultId(10L)).thenReturn(List.of());
        ChunkSet body = new ChunkSet();
        Chunk chunk = new Chunk();
        chunk.setChunkId("c-1");
        chunk.setContentType("PARAGRAPH");
        chunk.setContent("正文片");
        chunk.setCharCount(3);
        body.setChunks(List.of(chunk));
        byte[] bodyBytes = JsonUtil.toJsonStr(body).getBytes(StandardCharsets.UTF_8);
        when(fileStorage.getObject(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(), "artifacts", "art-c1")))
                .thenReturn(bodyBytes);
        when(fileStorage.getObject(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(), "artifacts", "art-c2")))
                .thenReturn(bodyBytes);

        LineageVO vo = service.lineage(10L);

        LineageNodeVO good = vo.getNodes().get(0);
        LineageNodeVO corrupt = vo.getNodes().get(1);
        // 快照可解析：策略版本与片长口径照常下发
        assertEquals("chunk-hybrid-v1", good.getStrategyVersion());
        assertTrue(good.getStats().containsKey("targetMaxLen"), String.valueOf(good.getStats()));
        // 快照损坏：策略版本留空、策略类口径整组留空，其余统计照常（如实展示无策略快照）
        assertNull(corrupt.getStrategyVersion());
        assertEquals(1, corrupt.getStats().get("chunkCount"));
        assertFalse(corrupt.getStats().containsKey("targetMaxLen"), String.valueOf(corrupt.getStats()));
        assertFalse(corrupt.getStats().containsKey("minMergeLen"), String.valueOf(corrupt.getStats()));
    }

    @Test
    void missingFileResultShouldReject40432() {
        when(fileResultDbService.getById(10L)).thenReturn(null);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.lineage(10L));
        assertEquals(ErrorCode.FILE_RESULT_NOT_FOUND, e.getErrorCode());
    }

    @Test
    void parseNodeShouldCarryProductStats() {
        WbPipelineTask parseTask = task(20L, PipelineStage.PARSE.name(), null, 10L, null);
        parseTask.setStatus(PipelineTaskStatus.SUCCESS.name());
        parseTask.setStartedAt(LocalDateTime.of(2024, 5, 1, 10, 0, 0));
        parseTask.setFinishedAt(LocalDateTime.of(2024, 5, 1, 10, 0, 8));
        when(fileResultDbService.getById(10L)).thenReturn(fileResultOfKb10());
        when(pipelineTaskDbService.listByFileResultId(10L)).thenReturn(List.of(parseTask));
        when(pipelineProductDbService.listByFileResultId(10L)).thenReturn(List.of(
                product(10L, PipelineStage.PARSE.name(), "art-p",
                        "{\"parserName\":\"pdfbox\",\"parserVersion\":\"3.0.4\"}")));
        when(chunkSetDbService.listByFileResultId(10L)).thenReturn(List.of());
        when(embeddingSetDbService.listByFileResultId(10L)).thenReturn(List.of());
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
        when(fileStorage.getObject(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(), "artifacts", "art-p")))
                .thenReturn(JsonUtil.toJsonStr(parseResult).getBytes(StandardCharsets.UTF_8));

        LineageVO vo = service.lineage(10L);

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

    @Test
    void parseNodeWithoutArtifactShouldFallBackToPageCount() {
        WbPipelineTask parseTask = task(20L, PipelineStage.PARSE.name(), null, 10L, null);
        parseTask.setStatus(PipelineTaskStatus.SUCCESS.name());
        parseTask.setStartedAt(LocalDateTime.of(2024, 5, 1, 10, 0, 0));
        parseTask.setFinishedAt(LocalDateTime.of(2024, 5, 1, 10, 0, 8));
        when(fileResultDbService.getById(10L)).thenReturn(fileResultOfKb10());
        when(pipelineTaskDbService.listByFileResultId(10L)).thenReturn(List.of(parseTask));
        when(pipelineProductDbService.listByFileResultId(10L)).thenReturn(List.of(
                product(10L, PipelineStage.PARSE.name(), "art-p",
                        "{\"parserName\":\"pdfbox\",\"parserVersion\":\"3.0.4\"}")));
        when(chunkSetDbService.listByFileResultId(10L)).thenReturn(List.of());
        when(embeddingSetDbService.listByFileResultId(10L)).thenReturn(List.of());
        // 产物本体：无判定单元数，页数取文件引用；无问题单元 → 成功态摘要为"无异常"
        ParseSource source = new ParseSource();
        source.setElements(List.of(element(ElementType.PARAGRAPH)));
        ParseResult parseResult = new ParseResult();
        parseResult.setSources(List.of(source));
        parseResult.setQuality(new QualityInfo());
        parseResult.setFile(new FileReference("f-1", "a.pdf", "sha", "application/pdf", 128));
        when(fileStorage.getObject(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(), "artifacts", "art-p")))
                .thenReturn(JsonUtil.toJsonStr(parseResult).getBytes(StandardCharsets.UTF_8));

        LineageVO vo = service.lineage(10L);

        LineageNodeVO node = vo.getNodes().get(0);
        assertEquals(128, node.getParseStats().getPageCount());
        assertEquals(1, node.getParseStats().getElementCount());
        assertNull(node.getParseStats().getFailedUnitCount());
        assertNull(node.getParseStats().getFailedFrom());
        assertNull(node.getParseStats().getFailedTo());
        assertEquals("无异常", node.getParseSummary());
    }

    /**
     * 产物不可读：统计与摘要都不下发 —— 统计没到手时不得断言"无异常"。
     *
     * <p>成功与部分成功两种终态都覆盖：两者的摘要判据同源（统计里的问题单元）。
     */
    @ParameterizedTest
    @EnumSource(value = PipelineTaskStatus.class, names = { "SUCCESS", "PARTIAL_SUCCESS" })
    void unreadableArtifactShouldLeaveParseStatsAndSummaryEmpty(PipelineTaskStatus status) {
        WbPipelineTask parseTask = task(20L, PipelineStage.PARSE.name(), null, 10L, null);
        parseTask.setStatus(status.name());
        parseTask.setStartedAt(LocalDateTime.of(2024, 5, 1, 10, 0, 0));
        parseTask.setFinishedAt(LocalDateTime.of(2024, 5, 1, 10, 0, 8));
        when(fileResultDbService.getById(10L)).thenReturn(fileResultOfKb10());
        when(pipelineTaskDbService.listByFileResultId(10L)).thenReturn(List.of(parseTask));
        when(pipelineProductDbService.listByFileResultId(10L)).thenReturn(List.of(
                product(10L, PipelineStage.PARSE.name(), "art-p",
                        "{\"parserName\":\"pdfbox\",\"parserVersion\":\"3.0.4\"}")));
        when(chunkSetDbService.listByFileResultId(10L)).thenReturn(List.of());
        when(embeddingSetDbService.listByFileResultId(10L)).thenReturn(List.of());
        when(fileStorage.getObject(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(), "artifacts", "art-p")))
                .thenThrow(new KnowledgeException(ErrorCode.FILE_NOT_FOUND, "对象读取失败"));

        LineageVO vo = service.lineage(10L);

        LineageNodeVO node = vo.getNodes().get(0);
        assertAll(
                () -> assertNull(node.getParseStats(), status + ": 产物不可读时不下发统计"),
                () -> assertNull(node.getParseSummary(), status + ": 产物不可读时不下发摘要"));
    }

    /**
     * 存储类读取失败（40454 对象在所属存储中不存在）：不吞掉，向上抛给统一异常处理 ——
     * 与"其它读取失败留空"是同一条读路径上的两种口径，不能混为一谈。
     */
    @Test
    void missingStorageObjectShouldPropagate40454() {
        WbPipelineTask parseTask = task(20L, PipelineStage.PARSE.name(), null, 10L, null);
        when(fileResultDbService.getById(10L)).thenReturn(fileResultOfKb10());
        when(pipelineTaskDbService.listByFileResultId(10L)).thenReturn(List.of(parseTask));
        when(pipelineProductDbService.listByFileResultId(10L)).thenReturn(List.of(
                product(10L, PipelineStage.PARSE.name(), "art-p",
                        "{\"parserName\":\"pdfbox\",\"parserVersion\":\"3.0.4\"}")));
        when(chunkSetDbService.listByFileResultId(10L)).thenReturn(List.of());
        when(embeddingSetDbService.listByFileResultId(10L)).thenReturn(List.of());
        when(fileStorage.getObject(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(), "artifacts", "art-p")))
                .thenThrow(new KnowledgeException(ErrorCode.STORAGE_OBJECT_MISSING, "对象已不存在"));

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.lineage(10L));

        assertEquals(ErrorCode.STORAGE_OBJECT_MISSING, e.getErrorCode());
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
        when(fileResultDbService.getById(10L)).thenReturn(fileResultOfKb10());
        when(pipelineTaskDbService.listByFileResultId(10L)).thenReturn(List.of(parseTask));
        when(pipelineProductDbService.listByFileResultId(10L)).thenReturn(List.of());
        when(chunkSetDbService.listByFileResultId(10L)).thenReturn(List.of());
        when(embeddingSetDbService.listByFileResultId(10L)).thenReturn(List.of());

        LineageVO vo = service.lineage(10L);

        assertEquals(1, vo.getNodes().size(), status + ": 未完成任务必须在血缘里可见");
        LineageNodeVO node = vo.getNodes().get(0);
        assertEquals(status.name(), node.getStatus());
        assertEquals(PipelineStage.PARSE.name(), node.getStage());
        assertNull(node.getProductId());
        assertNull(node.getParseStats());
        assertNull(node.getParseSummary());
    }

    /** 产物里的一个元素（只有类型参与汇总） */
    private ParseElement element(ElementType type) {
        return ParseElement.of("e-" + type.name(), type);
    }
    @Test
    void otherUserFileResultShouldReject40401() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResultOfKb10());
        // 归属看的是知识库归属：换一个登录用户，越权与"不存在"同样返回 40401
        SecurityTestSupport.loginOtherUser();

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.lineage(10L));
        assertEquals(ErrorCode.KB_NOT_FOUND, e.getErrorCode());
    }


    /** 让指定知识库归当前登录用户所有（归属校验要能过） */
    private void boundKnowledgeBase(Long id) {
        WbKnowledgeBase kb = new WbKnowledgeBase();
        kb.setId(id);
        kb.setUserId(SecurityTestSupport.VIEWER_ID);
        lenient().when(knowledgeBaseDbService.getActiveById(id)).thenReturn(kb);
    }

    /** 一次运行的文件结果（挂 10 号知识库，归属校验要能过） */
    private WbFileResult fileResultOfKb10() {
        WbFileResult fileResult = new WbFileResult();
        fileResult.setId(10L);
        fileResult.setKnowledgeBaseId(10L);
        return fileResult;
    }
}