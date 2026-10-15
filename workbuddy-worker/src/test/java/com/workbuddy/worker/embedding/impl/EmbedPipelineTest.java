package com.workbuddy.worker.embedding.impl;

import com.workbuddy.common.domain.chunk.Chunk;
import com.workbuddy.common.domain.chunk.ChunkSet;
import com.workbuddy.common.domain.embed.EmbedOutcome;
import com.workbuddy.common.domain.embed.EmbeddingRecord;
import com.workbuddy.common.domain.embed.EmbeddingRequest;
import com.workbuddy.common.domain.embed.EmbeddingResult;
import com.workbuddy.common.domain.embed.EmbeddingSet;
import com.workbuddy.common.enums.chunk.ChunkContentType;
import com.workbuddy.common.enums.embed.EmbedRecordStatus;
import com.workbuddy.common.enums.task.PipelineTaskErrorCode;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.model.catalog.StaticModelCatalog;
import com.workbuddy.model.gateway.ModelGatewayPort;
import com.workbuddy.worker.chunking.ChunkProperties;
import com.workbuddy.worker.chunking.strategy.ChunkStrategy;
import com.workbuddy.worker.chunking.strategy.ChunkStrategyParser;
import com.workbuddy.worker.embedding.EmbedContext;
import com.workbuddy.worker.embedding.strategy.EmbedAlgorithmSpec;
import com.workbuddy.worker.embedding.EmbedProperties;
import com.workbuddy.worker.embedding.strategy.EmbedStrategy;
import com.workbuddy.worker.embedding.strategy.EmbedStrategyParser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 向量化管线单测：前置校验 / 父片跳过 / 复用回溯命中 / 隔轮回溯 / OFF 强制重算 /
 * 四关拦截 / 批次重试 / 失败批次隔离 / 全失败 / 空切片集。
 *
 * @author cxxl
 */
class EmbedPipelineTest {

    private static final int DIM = 1024;

    private StubGateway gateway;

    private EmbedPipeline pipeline;

    @BeforeEach
    void setUp() {
        EmbedProperties properties = new EmbedProperties();
        properties.setRetryBackoffBaseMs(0);
        gateway = new StubGateway();
        pipeline = new EmbedPipeline(properties, gateway, new EmbedConsistencyChecker(), new IdentityTemplate());
    }

    // ---------------- 前置校验 ----------------

    @Test
    void windowIncompatibleShouldFail() {
        EmbedContext context = context(chunkSet(chunk("chunk-0001", "内容", "PARAGRAPH")),
                strategy("text-embedding-v4", true), List.of());
        context.setChunkStrategy(chunkStrategy("{\"routes\":{\"body\":{\"algorithm\":\"paragraph-aggregate\","
                + "\"params\":{\"softMaxLen\":20000}}}}"));
        context.setChunkProperties(new ChunkProperties());

        EmbedOutcome outcome = pipeline.embed(context);

        assertEquals(PipelineTaskStatus.FAILED, outcome.getSuggestedStatus());
        assertEquals(PipelineTaskErrorCode.EMBED_MODEL_INCOMPATIBLE.name(), outcome.getErrorCode());
        assertEquals(0, gateway.calls);
    }

    @Test
    void fallbackNoneShouldSkipWindowCheckWithWarning() {
        EmbedContext context = context(chunkSet(chunk("chunk-0001", "内容", "PARAGRAPH")),
                strategy("text-embedding-v4", true), List.of());
        context.setChunkStrategy(chunkStrategy("{\"routes\":{\"fallback\":{\"algorithm\":\"none\"}}}"));
        context.setChunkProperties(new ChunkProperties());

        EmbedOutcome outcome = pipeline.embed(context);

        // 告警只标记不阻断：跳过窗口校验产生告警 → PARTIAL_SUCCESS（平台口径：PARTIAL_SUCCESS 保留告警）
        assertEquals(PipelineTaskStatus.PARTIAL_SUCCESS, outcome.getSuggestedStatus());
        assertTrue(outcome.getWarnings().stream().anyMatch(w -> w.contains("不可推算")));
        assertEquals(1, gateway.calls);
    }

    @Test
    void emptyChunkSetShouldFailEmbedEmpty() {
        EmbedOutcome outcome = pipeline.embed(context(new ChunkSet(), strategy("text-embedding-v4", true), List.of()));

        assertEquals(PipelineTaskStatus.FAILED, outcome.getSuggestedStatus());
        assertEquals(PipelineTaskErrorCode.EMBED_EMPTY.name(), outcome.getErrorCode());
    }

    // ---------------- 筛选与编码 ----------------

    @Test
    void parentChunkShouldBeSkipped() {
        EmbedContext context = context(chunkSet(
                chunk("chunk-0001", "章节", "SECTION"),
                chunk("chunk-0002", "正文", "PARAGRAPH")), strategy("text-embedding-v4", true), List.of());

        EmbedOutcome outcome = pipeline.embed(context);

        assertEquals(PipelineTaskStatus.SUCCESS, outcome.getSuggestedStatus());
        assertEquals(2, outcome.getEmbeddingSet().getRecordCount());
        assertEquals(EmbedRecordStatus.SKIPPED.name(), outcome.getEmbeddingSet().getRecords().get(0).getStatus());
        assertEquals(1, gateway.calls);
        assertEquals(List.of("正文"), gateway.lastTexts());
    }

    @Test
    void emptyContentShouldBeSkipped() {
        EmbedContext context = context(chunkSet(
                chunk("chunk-0001", "", "PARAGRAPH"),
                chunk("chunk-0002", "有内容", "PARAGRAPH")), strategy("text-embedding-v4", true), List.of());

        EmbedOutcome outcome = pipeline.embed(context);

        assertEquals(PipelineTaskStatus.SUCCESS, outcome.getSuggestedStatus());
        assertEquals(EmbedRecordStatus.SKIPPED.name(), outcome.getEmbeddingSet().getRecords().get(0).getStatus());
        assertEquals(List.of("有内容"), gateway.lastTexts());
    }

    // ---------------- 复用判定 ----------------

    @Test
    void fullComputeWhenNoHistory() {
        EmbedContext context = context(chunkSet(
                chunk("chunk-0001", "第一段", "PARAGRAPH"),
                chunk("chunk-0002", "第二段", "PARAGRAPH")), strategy("text-embedding-v4", true), List.of());

        EmbedOutcome outcome = pipeline.embed(context);

        assertEquals(PipelineTaskStatus.SUCCESS, outcome.getSuggestedStatus());
        assertEquals(1, gateway.calls);
        assertEquals(0, outcome.getEmbeddingSet().getCachedCount());
        assertEquals("es-cs-doc-1-embed-default-v1", outcome.getEmbeddingSet().getEmbeddingSetId());
        for (EmbeddingRecord record : outcome.getEmbeddingSet().getRecords()) {
            assertEquals(EmbedRecordStatus.SUCCESS.name(), record.getStatus());
            assertEquals(DIM, record.getVector().size());
        }
    }

    @Test
    void reuseHitShouldSkipGatewayAndCopyVector() {
        List<EmbeddingSet> history = List.of(previousSet("prev-1", EmbedHashes.sha256Hex("第一段"), List.of(1.0f, 2.0f)));
        EmbedContext context = context(chunkSet(
                chunk("chunk-0001", "第一段", "PARAGRAPH"),
                chunk("chunk-0002", "第二段", "PARAGRAPH")), strategy("text-embedding-v4", true), history);

        EmbedOutcome outcome = pipeline.embed(context);

        assertEquals(PipelineTaskStatus.SUCCESS, outcome.getSuggestedStatus());
        assertEquals(1, gateway.calls); // 只有第二段走网关
        assertEquals(List.of("第二段"), gateway.lastTexts());
        EmbeddingSet set = outcome.getEmbeddingSet();
        assertEquals(1, set.getCachedCount());
        assertTrue(set.getRecords().get(0).isCacheHit());
        assertEquals(List.of(1.0f, 2.0f), set.getRecords().get(0).getVector());
        assertEquals(EmbedRecordStatus.CACHED.name(), set.getRecords().get(0).getStatus());
    }

    @Test
    void backtrackShouldCoverOlderLedgers() {
        // 隔轮切回：最近账本只有第一段，更旧账本才有第二段
        List<EmbeddingSet> history = List.of(
                previousSet("recent", EmbedHashes.sha256Hex("第一段"), List.of(1.0f)),
                previousSet("older", EmbedHashes.sha256Hex("第二段"), List.of(2.0f)));
        EmbedContext context = context(chunkSet(
                chunk("chunk-0001", "第一段", "PARAGRAPH"),
                chunk("chunk-0002", "第二段", "PARAGRAPH")), strategy("text-embedding-v4", true), history);

        EmbedOutcome outcome = pipeline.embed(context);

        assertEquals(PipelineTaskStatus.SUCCESS, outcome.getSuggestedStatus());
        assertEquals(0, gateway.calls); // 全部回溯命中
        assertEquals(2, outcome.getEmbeddingSet().getCachedCount());
        assertEquals(EmbedRecordStatus.CACHED.name(), outcome.getEmbeddingSet().getRecords().get(1).getStatus());
    }

    @Test
    void fullReuseShouldAvoidGatewayEntirely() {
        List<EmbeddingSet> history = List.of(previousSet("prev", EmbedHashes.sha256Hex("第一段"), List.of(1.0f)));
        EmbedContext context = context(chunkSet(chunk("chunk-0001", "第一段", "PARAGRAPH")),
                strategy("text-embedding-v4", true), history);

        EmbedOutcome outcome = pipeline.embed(context);

        assertEquals(0, gateway.calls);
        assertEquals(1, outcome.getEmbeddingSet().getCachedCount());
        assertEquals(PipelineTaskStatus.SUCCESS, outcome.getSuggestedStatus());
    }

    @Test
    void cacheOffShouldForceRecompute() {
        List<EmbeddingSet> history = List.of(previousSet("prev", EmbedHashes.sha256Hex("第一段"), List.of(1.0f)));
        EmbedContext context = context(chunkSet(chunk("chunk-0001", "第一段", "PARAGRAPH")),
                strategy("text-embedding-v4", false), history);

        EmbedOutcome outcome = pipeline.embed(context);

        assertEquals(1, gateway.calls);
        assertEquals(0, outcome.getEmbeddingSet().getCachedCount());
        assertEquals(EmbedRecordStatus.SUCCESS.name(), outcome.getEmbeddingSet().getRecords().get(0).getStatus());
        assertFalse(outcome.getEmbeddingSet().getRecords().get(0).isCacheHit());
    }

    // ---------------- 网关调用与四关 ----------------

    @Test
    void wrongDimensionShouldReject() {
        gateway.handler = texts -> vectors(texts.size(), 10);
        EmbedContext context = context(chunkSet(chunk("chunk-0001", "第一段", "PARAGRAPH")),
                strategy("text-embedding-v4", true), List.of());

        EmbedOutcome outcome = pipeline.embed(context);

        assertEquals(PipelineTaskStatus.FAILED, outcome.getSuggestedStatus());
        assertEquals(PipelineTaskErrorCode.EMBED_CONSISTENCY_FAILED.name(), outcome.getErrorCode());
    }

    @Test
    void retryableFailureShouldRetryThenSucceed() {
        gateway.failuresBeforeSuccess = 1;
        EmbedContext context = context(chunkSet(chunk("chunk-0001", "第一段", "PARAGRAPH")),
                strategy("text-embedding-v4", true), List.of());

        EmbedOutcome outcome = pipeline.embed(context);

        assertEquals(2, gateway.calls);
        assertEquals(PipelineTaskStatus.SUCCESS, outcome.getSuggestedStatus());
    }

    @Test
    void missingStrategyShouldFailEmbedFailed() {
        // 上下文无策略（快照缺失或损坏由 Runner 拦截）：管线自身同样拒绝，不构造默认策略
        EmbedContext context = context(chunkSet(chunk("chunk-0001", "第一段", "PARAGRAPH")), null, List.of());

        EmbedOutcome outcome = pipeline.embed(context);

        assertEquals(PipelineTaskStatus.FAILED, outcome.getSuggestedStatus());
        assertEquals(PipelineTaskErrorCode.EMBED_FAILED.name(), outcome.getErrorCode());
        assertEquals("任务未带向量化策略快照，无法向量化", outcome.getErrorMsg());
        assertNull(outcome.getEmbeddingSet());
    }

    @Test
    void batchAllFailedShouldFailEmbedFailed() {
        gateway.alwaysFail = true;
        EmbedStrategy strategy = strategy("text-embedding-v4", true);
        strategy.setMaxRetries(0);
        EmbedContext context = context(chunkSet(chunk("chunk-0001", "第一段", "PARAGRAPH")), strategy, List.of());

        EmbedOutcome outcome = pipeline.embed(context);

        assertEquals(PipelineTaskStatus.FAILED, outcome.getSuggestedStatus());
        assertEquals(PipelineTaskErrorCode.EMBED_FAILED.name(), outcome.getErrorCode());
        assertEquals(EmbedRecordStatus.FAILED.name(), outcome.getEmbeddingSet().getRecords().get(0).getStatus());
    }

    @Test
    void partialBatchFailureShouldBePartialSuccess() {
        EmbedStrategy strategy = strategy("text-embedding-v4", true);
        strategy.setBatchSize(1);
        strategy.setMaxRetries(0);
        gateway.failOnTexts = texts -> texts.stream().anyMatch("失败段"::equals);
        EmbedContext context = context(chunkSet(
                chunk("chunk-0001", "成功段", "PARAGRAPH"),
                chunk("chunk-0002", "失败段", "PARAGRAPH")), strategy, List.of());

        EmbedOutcome outcome = pipeline.embed(context);

        assertEquals(PipelineTaskStatus.PARTIAL_SUCCESS, outcome.getSuggestedStatus());
        assertEquals(EmbedRecordStatus.SUCCESS.name(), outcome.getEmbeddingSet().getRecords().get(0).getStatus());
        assertEquals(EmbedRecordStatus.FAILED.name(), outcome.getEmbeddingSet().getRecords().get(1).getStatus());
    }

    // ---------------- fixtures ----------------

    /** 用例基线策略：模板名/版本与 init.sql 种子行同名同版本，参数按代码侧模板补全 */
    private EmbedStrategy strategy(String model, boolean cacheOn) {
        EmbedStrategy strategy = new EmbedStrategy();
        strategy.setName("embed-default");
        strategy.setVersion("v1");
        strategy.setModel(model);
        strategy.setCacheEnabled(cacheOn ? EmbedStrategy.ON : EmbedStrategy.OFF);
        return EmbedAlgorithmSpec.normalize(strategy, new EmbedProperties(), new StaticModelCatalog());
    }

    private ChunkStrategy chunkStrategy(String json) {
        return new ChunkStrategyParser(new ChunkProperties()).parse(json);
    }

    private ChunkSet chunkSet(Chunk... chunks) {
        ChunkSet set = new ChunkSet();
        set.setChunkSetId("cs-doc-1");
        set.setFileResultId(5L);
        set.setDocumentId("doc-1");
        set.setStrategyVersion("chunk-hybrid-v1");
        set.setChunks(new ArrayList<>(List.of(chunks)));
        set.setChunkCount(chunks.length);
        return set;
    }

    private Chunk chunk(String chunkId, String content, String contentType) {
        Chunk chunk = new Chunk();
        chunk.setChunkId(chunkId);
        chunk.setContent(content);
        chunk.setContentType(contentType);
        chunk.setCharCount(content == null ? 0 : content.length());
        return chunk;
    }

    private EmbeddingSet previousSet(String setId, String hash, List<Float> vector) {
        EmbeddingSet set = new EmbeddingSet();
        set.setEmbeddingSetId(setId);
        set.setStrategyVersion("embed-default-v1");
        EmbeddingRecord record = new EmbeddingRecord();
        record.setInputTextHash(hash);
        record.setStatus(EmbedRecordStatus.SUCCESS.name());
        record.setVector(vector);
        set.setRecords(List.of(record));
        return set;
    }

    private EmbedContext context(ChunkSet chunkSet, EmbedStrategy strategy, List<EmbeddingSet> history) {
        EmbedContext context = new EmbedContext();
        context.setChunkSet(chunkSet);
        context.setChunkSetRef(9L);
        context.setFileResultId(5L);
        context.setStrategy(strategy);
        context.setProperties(new EmbedProperties());
        context.setReuseCandidates(new ArrayList<>(history));
        return context;
    }

    private static List<List<Float>> vectors(int count, int dimension) {
        List<List<Float>> vectors = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            List<Float> vector = new ArrayList<>();
            for (int j = 0; j < dimension; j++) {
                vector.add((float) (i + j));
            }
            vectors.add(vector);
        }
        return vectors;
    }

    /** 可编程模型桩 */
    private static final class StubGateway implements ModelGatewayPort {

        int calls;

        Function<List<String>, List<List<Float>>> handler;

        int failuresBeforeSuccess;

        boolean alwaysFail;

        Function<List<String>, Boolean> failOnTexts;

        private List<String> lastTexts;

        @Override
        public EmbeddingResult embed(EmbeddingRequest request) {
            calls++;
            lastTexts = request.getTexts();
            if (failuresBeforeSuccess > 0) {
                failuresBeforeSuccess--;
                throw new IllegalStateException("网关暂不可用（第 " + calls + " 次）");
            }
            if (alwaysFail || (failOnTexts != null && Boolean.TRUE.equals(failOnTexts.apply(request.getTexts())))) {
                throw new IllegalStateException("网关失败");
            }
            EmbeddingResult result = new EmbeddingResult();
            result.setModel(request.getModel());
            result.setRequestId(request.getRequestId());
            if (handler != null) {
                result.setEmbeddings(handler.apply(request.getTexts()));
            } else {
                result.setEmbeddings(vectors(request.getTexts().size(), DIM));
            }
            result.setDimension(result.getEmbeddings().get(0).size());
            return result;
        }

        List<String> lastTexts() {
            return lastTexts;
        }
    }
}
