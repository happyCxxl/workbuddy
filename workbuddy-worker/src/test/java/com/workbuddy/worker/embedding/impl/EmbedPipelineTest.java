package com.workbuddy.worker.embedding.impl;

import com.workbuddy.common.domain.chunk.Chunk;
import com.workbuddy.common.domain.chunk.ChunkSet;
import com.workbuddy.common.domain.embed.EmbedOutcome;
import com.workbuddy.common.domain.embed.EmbeddingRecord;
import com.workbuddy.common.domain.embed.EmbeddingSet;
import com.workbuddy.common.domain.task.StepLogInfo;
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
 * <p>守护 {@link EmbedPipeline#embed} 的失败分类与产出结构。模型调用层尚未接入
 * （{@code embedBatch} 固定抛错），因此成功路径只经「全部复用命中」验证；
 * 模型成功后的回填分支当前不可达，未纳入用例。
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
     *
     * <p>守护 {@code embed} 的首个闸门：去掉快照判空即失败（会抛空指针而不是返回失败）。
     */
    @Test
    void failsWhenStrategyMissing() {
        context.setStrategy(null);

        EmbedOutcome outcome = pipeline.embed(context);

        assertEquals(PipelineTaskErrorCode.EMBED_FAILED.name(), outcome.getErrorCode());
    }

    /**
     * 模型名不在目录内时按模型不兼容失败。
     *
     * <p>守护 {@code precheck} 的目录校验：移除 {@code EmbeddingModel.of} 判空即失败。
     */
    @Test
    void failsWhenModelUnknown() {
        strategy.setModel("not-a-model");

        EmbedOutcome outcome = pipeline.embed(context);

        assertEquals(PipelineTaskErrorCode.EMBED_MODEL_INCOMPATIBLE.name(), outcome.getErrorCode());
    }

    /**
     * 模型在目录内但未启用时同样按模型不兼容失败。
     *
     * <p>守护 {@code precheck} 的启用校验：去掉 {@code enabled()} 判断即失败。
     */
    @Test
    void failsWhenModelDisabled() {
        strategy.setModel("text-embedding-v3");

        EmbedOutcome outcome = pipeline.embed(context);

        assertEquals(PipelineTaskErrorCode.EMBED_MODEL_INCOMPATIBLE.name(), outcome.getErrorCode());
    }

    /**
     * 缺少上下文窗口声明时按模型不兼容失败。
     *
     * <p>守护 {@code precheck} 的窗口声明校验：把 {@code <= 0} 改成 {@code < 0} 即失败。
     */
    @Test
    void failsWhenContextWindowMissing() {
        strategy.setContextWindowTokens(0);

        EmbedOutcome outcome = pipeline.embed(context);

        assertEquals(PipelineTaskErrorCode.EMBED_MODEL_INCOMPATIBLE.name(), outcome.getErrorCode());
    }

    /**
     * 上游切片产物为空时按空输入失败。
     *
     * <p>守护 {@code embed} 的空集合判定：去掉 {@code chunkSet} 判空即失败。
     */
    @Test
    void failsWhenChunkSetMissing() {
        EmbedOutcome outcome = pipeline.embed(context);

        assertEquals(PipelineTaskErrorCode.EMBED_EMPTY.name(), outcome.getErrorCode());
    }

    /**
     * 切片全部被跳过（父片且未开启向量化父片）时按无可用切片失败。
     *
     * <p>守护 {@code encode} 的父片跳过规则：把 SECTION 判断去掉即失败。
     */
    @Test
    void failsWhenAllChunksSkipped() {
        context.setChunkSet(chunkSet(sectionChunk("c1"), sectionChunk("c2")));

        EmbedOutcome outcome = pipeline.embed(context);

        assertEquals(PipelineTaskErrorCode.EMBED_EMPTY.name(), outcome.getErrorCode());
    }

    /**
     * 空文本且开启跳过时不计入候选，按无可用切片失败。
     *
     * <p>守护 {@code encode} 的空文本跳过：去掉 {@code skipEmptyOn} 分支即失败。
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
     *
     * <p>守护 {@code resolveReuse} 的哈希命中与 {@code assemble} 的计数口径：
     * 让哈希匹配失败或多算复用数即失败。ID 拼接格式属实现细节，不断言。
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
        assertEquals(DIMENSION, set.getDimension());
        assertEquals(EmbedRecordStatus.CACHED.name(), set.getRecords().getFirst().getStatus());
    }

    /**
     * 复用关闭时不查账本，记录全部送模型（本环境必然失败）。
     *
     * <p>守护 {@code embed} 的复用开关：忽略 {@code cacheOn} 直接复用即失败。
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
     * 模型调用全部失败时按向量化失败收口，每批留一条告警。
     *
     * <p>守护 {@code invokeBatches} 的批次隔离：3 条记录按批大小 2 分两批，失败不互相掩盖。
     */
    @Test
    void failsWhenAllBatchesFail() {
        context.setChunkSet(chunkSet(
                textChunk("c1", "正文一"), textChunk("c2", "正文二"), textChunk("c3", "正文三")));
        when(template.render(any(), any())).thenReturn("正文一");

        EmbedOutcome outcome = pipeline.embed(context);

        assertEquals(PipelineTaskErrorCode.EMBED_FAILED.name(), outcome.getErrorCode());
        assertEquals(2, outcome.getWarnings().size());
    }

    /**
     * 步骤日志固定五段，字段可用。
     *
     * <p>守护 {@code buildStepLogs} 的阶段划分：少写一段或漏填步骤名即失败。
     * 步骤名属展示文案，只断言非空，不断言具体文字。
     */
    @Test
    void buildsFiveStepLogs() {
        context.setChunkSet(chunkSet(textChunk("c1", "正文一")));
        when(template.render(any(), any())).thenReturn("正文一");

        EmbedOutcome outcome = pipeline.embed(context);

        List<StepLogInfo> logs = outcome.getStepLogs();
        assertEquals(5, logs.size());
        for (StepLogInfo log : logs) {
            assertNotNull(log.getStepName());
            assertTrue(log.getStepName().length() > 0);
        }
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
