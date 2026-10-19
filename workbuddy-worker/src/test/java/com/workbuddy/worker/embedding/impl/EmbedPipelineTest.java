package com.workbuddy.worker.embedding.impl;

import com.workbuddy.common.domain.chunk.Chunk;
import com.workbuddy.common.domain.chunk.ChunkSet;
import com.workbuddy.common.domain.embed.EmbedOutcome;
import com.workbuddy.common.domain.embed.EmbeddingRecord;
import com.workbuddy.common.domain.embed.EmbeddingSet;
import com.workbuddy.common.enums.chunk.ChunkContentType;
import com.workbuddy.common.enums.embed.EmbedMetric;
import com.workbuddy.common.enums.embed.EmbedRecordStatus;
import com.workbuddy.common.enums.task.PipelineTaskErrorCode;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.worker.embedding.EmbedContext;
import com.workbuddy.worker.embedding.EmbedProperties;
import com.workbuddy.worker.embedding.check.ConsistencyChecker;
import com.workbuddy.worker.embedding.strategy.EmbedStrategy;
import com.workbuddy.worker.embedding.template.InputTemplatePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 向量化编排：前置校验、筛选、账本复用与批次失败隔离。
 *
 * <p>模型调用层尚未接入（批次一律失败），因此成功路径只经「全部复用命中」验证；
 * 其余用例覆盖各前置闸门与失败隔离口径。
 */
@ExtendWith(MockitoExtension.class)
class EmbedPipelineTest {

    private static final int DIMENSION = 1024;

    @Mock
    private ConsistencyChecker checker;

    @Mock
    private InputTemplatePort template;

    private EmbedProperties properties;

    private EmbedPipeline pipeline;

    private EmbedContext context;

    private EmbedStrategy strategy;

    /**
     * 每个用例重建编排器与上下文：模型用已启用的 text-embedding-v4，重试 0 次避免等待退避。
     */
    @BeforeEach
    void setUp() {
        properties = new EmbedProperties();
        properties.setMaxRetries(0);
        pipeline = new EmbedPipeline(properties, checker, template);

        strategy = new EmbedStrategy();
        strategy.setName("embed");
        strategy.setVersion("test");
        strategy.setModel("text-embedding-v4");
        strategy.setContextWindowTokens(8192);
        strategy.setDimension(DIMENSION);
        strategy.setMetric(EmbedMetric.COSINE.key());
        strategy.setNormalized(true);
        strategy.setBatchSize(2);
        strategy.setMaxRetries(0);
        strategy.setCacheEnabled("ON");
        strategy.setIncludeParent("OFF");
        strategy.setSkipEmpty("ON");

        context = new EmbedContext();
        context.setStrategy(strategy);
        context.setFileResultId(7L);
        context.setChunkSetRef(9L);
    }

    /**
     * 未带策略快照时直接失败，不进入任何环节。
     */
    @Test
    void failsWhenStrategyMissing() {
        context.setStrategy(null);

        EmbedOutcome outcome = pipeline.embed(context);

        assertEquals(PipelineTaskErrorCode.EMBED_FAILED.name(), outcome.getErrorCode());
    }

    /**
     * 模型名不在目录内时按模型不兼容失败。
     */
    @Test
    void failsWhenModelUnknown() {
        strategy.setModel("not-a-model");

        EmbedOutcome outcome = pipeline.embed(context);

        assertEquals(PipelineTaskErrorCode.EMBED_MODEL_INCOMPATIBLE.name(), outcome.getErrorCode());
    }

    /**
     * 模型在目录内但未启用时同样按模型不兼容失败。
     */
    @Test
    void failsWhenModelDisabled() {
        strategy.setModel("text-embedding-v3");

        EmbedOutcome outcome = pipeline.embed(context);

        assertEquals(PipelineTaskErrorCode.EMBED_MODEL_INCOMPATIBLE.name(), outcome.getErrorCode());
    }

    /**
     * 缺少上下文窗口声明时按模型不兼容失败（窗口校验依赖该声明）。
     */
    @Test
    void failsWhenContextWindowMissing() {
        strategy.setContextWindowTokens(0);

        EmbedOutcome outcome = pipeline.embed(context);

        assertEquals(PipelineTaskErrorCode.EMBED_MODEL_INCOMPATIBLE.name(), outcome.getErrorCode());
    }

    /**
     * 上游切片产物为空时按空输入失败。
     */
    @Test
    void failsWhenChunkSetMissing() {
        EmbedOutcome outcome = pipeline.embed(context);

        assertEquals(PipelineTaskErrorCode.EMBED_EMPTY.name(), outcome.getErrorCode());
    }

    /**
     * 切片全部被跳过（父片且未开启向量化父片）时按无可用切片失败。
     */
    @Test
    void failsWhenAllChunksSkipped() {
        context.setChunkSet(chunkSet(sectionChunk("c1"), sectionChunk("c2")));

        EmbedOutcome outcome = pipeline.embed(context);

        assertEquals(PipelineTaskErrorCode.EMBED_EMPTY.name(), outcome.getErrorCode());
    }

    /**
     * 空文本且开启跳过时不计入候选，按无可用切片失败。
     */
    @Test
    void failsWhenTextBlankAndSkipEmptyOn() {
        context.setChunkSet(chunkSet(textChunk("c1", "")));
        when(template.render(any(), any())).thenReturn("");

        EmbedOutcome outcome = pipeline.embed(context);

        assertEquals(PipelineTaskErrorCode.EMBED_EMPTY.name(), outcome.getErrorCode());
    }

    /**
     * 全部命中复用账本时零模型调用，直接组装并标记成功。
     */
    @Test
    void succeedsWhenAllRecordsReused() {
        context.setChunkSet(chunkSet(textChunk("c1", "正文一")));
        when(template.render(any(), any())).thenReturn("正文一");
        context.setReuseCandidates(List.of(history("正文一", 0.5f)));

        EmbedOutcome outcome = pipeline.embed(context);

        assertEquals(PipelineTaskStatus.SUCCESS, outcome.getSuggestedStatus());
        EmbeddingSet set = outcome.getEmbeddingSet();
        assertNotNull(set);
        assertEquals(1, set.getCachedCount());
        assertEquals(1, set.getRecordCount());
        assertEquals("es-cs-1-embed-test", set.getEmbeddingSetId());
        assertEquals(DIMENSION, set.getDimension());
        assertEquals(EmbedRecordStatus.CACHED.name(), set.getRecords().getFirst().getStatus());
        assertEquals("emb-0001", set.getRecords().getFirst().getEmbeddingId());
    }

    /**
     * 复用关闭时不查账本，记录全部送模型（本环境必然失败）。
     */
    @Test
    void skipsReuseWhenCacheDisabled() {
        context.setChunkSet(chunkSet(textChunk("c1", "正文一")));
        strategy.setCacheEnabled("OFF");
        when(template.render(any(), any())).thenReturn("正文一");
        context.setReuseCandidates(List.of(history("正文一", 0.5f)));

        EmbedOutcome outcome = pipeline.embed(context);

        assertEquals(PipelineTaskErrorCode.EMBED_FAILED.name(), outcome.getErrorCode());
    }

    /**
     * 模型调用全部失败时按向量化失败收口，并按批次数留告警（失败隔离不互相污染）。
     */
    @Test
    void failsWhenAllBatchesFail() {
        context.setChunkSet(chunkSet(
                textChunk("c1", "正文一"), textChunk("c2", "正文二"), textChunk("c3", "正文三")));
        when(template.render(any(), any())).thenReturn("正文一");

        EmbedOutcome outcome = pipeline.embed(context);

        assertEquals(PipelineTaskErrorCode.EMBED_FAILED.name(), outcome.getErrorCode());
        assertEquals(2, outcome.getWarnings().size());
        assertTrue(outcome.getWarnings().getFirst().contains("模型调用失败"));
    }

    /**
     * 步骤日志固定五段，顺序与阶段一一对应。
     */
    @Test
    void buildsFiveStepLogs() {
        context.setChunkSet(chunkSet(textChunk("c1", "正文一")));
        when(template.render(any(), any())).thenReturn("正文一");

        EmbedOutcome outcome = pipeline.embed(context);

        List<String> names = outcome.getStepLogs().stream().map(log -> log.getStepName()).toList();
        assertEquals(List.of("前置校验", "筛选与编码", "复用判定", "模型调用", "四关校验"), names);
    }

    /**
     * 构造切片集合。
     *
     * @param chunks 切片
     * @return 切片集合
     */
    private ChunkSet chunkSet(Chunk... chunks) {
        ChunkSet set = new ChunkSet();
        set.setChunkSetId("cs-1");
        set.setChunks(new ArrayList<>(List.of(chunks)));
        set.setChunkCount(chunks.length);
        return set;
    }

    /**
     * 正文切片。
     *
     * @param id      切片 ID
     * @param content 正文
     * @return 切片
     */
    private Chunk textChunk(String id, String content) {
        Chunk chunk = new Chunk();
        chunk.setChunkId(id);
        chunk.setContent(content);
        chunk.setContentType(ChunkContentType.PARAGRAPH.name());
        return chunk;
    }

    /**
     * 父片（节）切片。
     *
     * @param id 切片 ID
     * @return 切片
     */
    private Chunk sectionChunk(String id) {
        Chunk chunk = new Chunk();
        chunk.setChunkId(id);
        chunk.setContent("节正文");
        chunk.setContentType(ChunkContentType.SECTION.name());
        return chunk;
    }

    /**
     * 构造一条可复用的历史账本（按输入文本哈希命中）。
     *
     * @param text       历史输入文本
     * @param firstValue 向量首维（其余补零到模型维度）
     * @return 历史集合
     */
    private EmbeddingSet history(String text, float firstValue) {
        EmbeddingRecord record = new EmbeddingRecord();
        record.setInputTextHash(EmbedHashes.sha256Hex(text));
        List<Float> embedding = new ArrayList<>();
        embedding.add(firstValue);
        for (int i = 1; i < DIMENSION; i++) {
            embedding.add(0f);
        }
        record.setVector(embedding);

        EmbeddingSet set = new EmbeddingSet();
        set.setRecords(List.of(record));
        return set;
    }
}
