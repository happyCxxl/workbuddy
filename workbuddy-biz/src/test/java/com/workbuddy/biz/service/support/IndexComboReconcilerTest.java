package com.workbuddy.biz.service.support;

import com.workbuddy.biz.service.db.WbChunkSetDbService;
import com.workbuddy.biz.service.db.WbEmbeddingSetDbService;
import com.workbuddy.biz.service.db.WbFileResultDbService;
import com.workbuddy.common.domain.embed.EmbeddingRecord;
import com.workbuddy.common.domain.embed.EmbeddingSet;
import com.workbuddy.common.domain.entity.WbChunkSet;
import com.workbuddy.common.domain.entity.WbEmbeddingSet;
import com.workbuddy.common.domain.entity.WbFileResult;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.worker.indexing.ComboSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 索引组合对账器单测（单一取数口径）：
 * 对账不变量——组合必须携带三环节策略维度（缺少该维度的快照由快照边界断言拒绝，
 * 对账器按同一维度判定不完整，两者口径一致）；
 * 单一取数口径——selectComboProducts（血统匹配/缺口/血统不符）、latestChunkMap（同策略取 id 最大）、
 * computeExpected 尊重 LIST 范围（范围外文件不参与）。
 */
@ExtendWith(MockitoExtension.class)
class IndexComboReconcilerTest {

    /** 产物所在的数据源 */
    private static final Long SOURCE_ID = 1L;

    @Mock
    private WbFileResultDbService fileResultDbService;
    @Mock
    private WbChunkSetDbService chunkSetDbService;
    @Mock
    private WbEmbeddingSetDbService embeddingSetDbService;
    @Mock
    private IndexLineageResolver lineageResolver;
    @Mock
    private IndexRowAssembler indexRowAssembler;

    private IndexComboReconciler reconciler;

    @BeforeEach
    void setUp() {
        reconciler = new IndexComboReconciler(fileResultDbService, chunkSetDbService,
                embeddingSetDbService, lineageResolver, indexRowAssembler);
    }

    private WbChunkSet chunkRow(Long id, String strategy) {
        WbChunkSet row = new WbChunkSet();
        row.setId(id);
        row.setFileResultId(10L);
        row.setChunkStrategyVersion(strategy);
        row.setUpstreamProductId(100L);
        return row;
    }

    private WbEmbeddingSet embedRow() {
        WbEmbeddingSet row = new WbEmbeddingSet();
        row.setId(2L);
        row.setFileResultId(10L);
        row.setStrategyVersion("embed-default-v1");
        // 读向量产物按记录里的数据源与桶名定位：行对象必须带上这两段与对象键
        row.setArtifactId("artifact-2");
        row.setStorageType(StorageType.MINIO.getCode());
        row.setStorageSourceId(SOURCE_ID);
        row.setBucket("artifacts");
        return row;
    }

    /**
     * 组合缺三环节维度：对账判不完整并给废弃重灌原因。
     *
     * <p>守护 {@code computeExpected}：缺失维度算成一致即失败。
     */
    @Test
    void computeExpectedShouldRejectLegacyComboWithoutStageStrategies() {
        ComboSnapshot legacy = new ComboSnapshot();

        IndexComboReconciler.ComboExpectation expectation = reconciler.computeExpected(1L, legacy);

        assertFalse(expectation.complete());
        assertEquals("组合缺失环节策略维度（需废弃重灌）", expectation.gap());
        verifyNoInteractions(fileResultDbService);
    }

    /**
     * 组合缺预处理维度：同样判不完整。
     *
     * <p>守护 {@code computeExpected}：半套维度可对账即失败。
     */
    @Test
    void computeExpectedShouldRejectPartialComboMissingPreprocess() {
        ComboSnapshot partial = ComboSnapshot.of(null, "chunk-hybrid-v1", "embed-default-v1");

        IndexComboReconciler.ComboExpectation expectation = reconciler.computeExpected(1L, partial);

        assertFalse(expectation.complete());
        assertEquals("组合缺失环节策略维度（需废弃重灌）", expectation.gap());
        verifyNoInteractions(fileResultDbService);
    }

    /**
     * 单一取数：血统匹配时返回切片与向量行。
     *
     * <p>守护 {@code selectComboProducts}：匹配却报缺口即失败。
     */
    @Test
    void selectComboProductsShouldReturnRowsWhenLineageMatches() {
        WbChunkSet chunk = chunkRow(1L, "chunk-hybrid-v1");
        WbEmbeddingSet embed = embedRow();
        when(lineageResolver.resolvePreprocessStrategy(100L)).thenReturn("preproc-default-v1");

        IndexComboReconciler.ComboProducts products = reconciler.selectComboProducts(
                ComboSnapshot.of("preproc-default-v1", "chunk-hybrid-v1", "embed-default-v1"),
                10L, Map.of("10#chunk-hybrid-v1", chunk), Map.of("10#embed-default-v1", embed));

        assertTrue(products.complete());
        assertEquals(chunk, products.chunkRow());
        assertEquals(embed, products.embedRow());
    }

    /**
     * 单一取数：缺产物给缺口原因。
     *
     * <p>守护 {@code selectComboProducts}：缺产物算齐即失败。
     */
    @Test
    void selectComboProductsShouldReportGapWhenProductMissing() {
        IndexComboReconciler.ComboProducts products = reconciler.selectComboProducts(
                ComboSnapshot.of("preproc-default-v1", "chunk-hybrid-v1", "embed-default-v1"),
                10L, Map.of(), Map.of());

        assertFalse(products.complete());
        assertTrue(products.gap().contains("缺少组合产物"), products.gap());
    }

    /**
     * 单一取数：血统不符给缺口原因（不拿异血统顶替）。
     *
     * <p>守护 {@code selectComboProducts}：异血统被采用即失败。
     */
    @Test
    void selectComboProductsShouldReportGapWhenLineageMismatch() {
        WbChunkSet chunk = chunkRow(1L, "chunk-hybrid-v1");
        when(lineageResolver.resolvePreprocessStrategy(100L)).thenReturn("preproc-other-v2");

        IndexComboReconciler.ComboProducts products = reconciler.selectComboProducts(
                ComboSnapshot.of("preproc-default-v1", "chunk-hybrid-v1", "embed-default-v1"),
                10L, Map.of("10#chunk-hybrid-v1", chunk),
                Map.of("10#embed-default-v1", embedRow()));

        assertFalse(products.complete());
        assertTrue(products.gap().contains("血统与组合预处理策略不符"), products.gap());
    }

    /**
     * 切片行映射：同策略取 id 最大的行。
     *
     * <p>守护 {@code latestChunkMap}：取旧行即失败。
     */
    @Test
    void latestChunkMapShouldKeepLatestById() {
        when(chunkSetDbService.listByFileResultIds(anyList())).thenReturn(List.of(
                chunkRow(1L, "chunk-a-v1"), chunkRow(3L, "chunk-a-v1")));

        Map<String, WbChunkSet> latest = reconciler.latestChunkMap(List.of(10L));

        assertEquals(3L, latest.get("10#chunk-a-v1").getId());
    }

    /**
     * LIST 范围：只取范围内文件，统计与样本口径一致。
     *
     * <p>守护 {@code computeExpected}：范围外文件混入即失败。
     */
    @Test
    void computeExpectedShouldRespectListScope() {
        WbFileResult file10 = new WbFileResult();
        file10.setId(10L);
        file10.setKnowledgeBaseId(1L);
        WbFileResult file11 = new WbFileResult();
        file11.setId(11L);
        file11.setKnowledgeBaseId(1L);
        when(fileResultDbService.listByKb(1L)).thenReturn(List.of(file10, file11));
        WbChunkSet chunk = chunkRow(1L, "chunk-hybrid-v1");
        WbEmbeddingSet embed = embedRow();
        embed.setArtifactId("artifact-2");
        embed.setDimension(1024);
        when(chunkSetDbService.listByFileResultIds(List.of(10L))).thenReturn(List.of(chunk));
        when(embeddingSetDbService.listByFileResultIds(List.of(10L))).thenReturn(List.of(embed));
        when(lineageResolver.resolvePreprocessStrategy(100L)).thenReturn("preproc-default-v1");
        EmbeddingSet artifact = new EmbeddingSet();
        EmbeddingRecord record = new EmbeddingRecord();
        record.setChunkId("chunk-1");
        record.setStatus("SUCCESS");
        record.setVector(List.of(1.0f, 2.0f));
        record.setInputText("投标保证金叁万元");
        artifact.setRecords(List.of(record));
        when(indexRowAssembler.readEmbeddingSet(ObjectRef.ofEmbeddingSet(embed))).thenReturn(artifact);
        ComboSnapshot combo = ComboSnapshot.of("preproc-default-v1", "chunk-hybrid-v1", "embed-default-v1");
        combo.setFileScopeMode("LIST");
        combo.setFileResultIds(List.of(10L));

        IndexComboReconciler.ComboExpectation expectation = reconciler.computeExpected(1L, combo);

        assertTrue(expectation.complete());
        assertEquals(Set.of("chunk-1"), expectation.chunkIds());
        assertEquals(1, expectation.vectorCount());
        assertEquals(1024, expectation.dimension());
        assertEquals("投标保证金叁...", expectation.sampleKeyword()); // StrUtil.maxLength 截断补省略号
        // 范围外文件 11 不参与（批次查询只取范围内文件）
        verify(chunkSetDbService, never()).listByFileResultIds(List.of(11L));
    }

    /**
     * LIST 范围为空：直接判不完整并给出原因。
     *
     * <p>守护 {@code computeExpected} 的空范围兜底：空范围算成"一致"会让对账永远通过。
     */
    @Test
    void computeExpectedShouldReportEmptyListScope() {
        when(fileResultDbService.listByKb(1L)).thenReturn(List.of(fileRow(10L)));
        ComboSnapshot combo = ComboSnapshot.of("preproc-default-v1", "chunk-hybrid-v1", "embed-default-v1");
        combo.setFileScopeMode("LIST");
        combo.setFileResultIds(List.of());

        IndexComboReconciler.ComboExpectation expectation = reconciler.computeExpected(1L, combo);

        assertFalse(expectation.complete());
        assertEquals("组合范围内无文件", expectation.gap());
        verify(chunkSetDbService, never()).listByFileResultIds(anyList());
    }

    /**
     * LIST 范围含外库文件：判不完整并点名文件。
     *
     * <p>守护 {@code computeExpected} 的归属校验：外库文件混进范围会按错的库建集合。
     */
    @Test
    void computeExpectedShouldReportForeignFileInListScope() {
        when(fileResultDbService.listByKb(1L)).thenReturn(List.of(fileRow(10L)));
        ComboSnapshot combo = ComboSnapshot.of("preproc-default-v1", "chunk-hybrid-v1", "embed-default-v1");
        combo.setFileScopeMode("LIST");
        combo.setFileResultIds(List.of(99L));

        IndexComboReconciler.ComboExpectation expectation = reconciler.computeExpected(1L, combo);

        assertFalse(expectation.complete());
        assertTrue(expectation.gap().contains("非本库文件: 99"), expectation.gap());
    }

    /**
     * 组合产物缺失：对账期望判不完整并给出缺口原因。
     *
     * <p>守护 {@code computeExpected} 的产物缺失分支：缺产物算成一致会让校验放行。
     */
    @Test
    void computeExpectedShouldReportGapWhenProductsMissing() {
        when(fileResultDbService.listByKb(1L)).thenReturn(List.of(fileRow(10L)));
        when(chunkSetDbService.listByFileResultIds(List.of(10L))).thenReturn(List.of());
        when(embeddingSetDbService.listByFileResultIds(List.of(10L))).thenReturn(List.of());
        ComboSnapshot combo = ComboSnapshot.of("preproc-default-v1", "chunk-hybrid-v1", "embed-default-v1");

        IndexComboReconciler.ComboExpectation expectation = reconciler.computeExpected(1L, combo);

        assertFalse(expectation.complete());
        assertTrue(expectation.gap().contains("缺少组合产物"), expectation.gap());
    }

    /**
     * 向量产物读不到：对账期望判不完整并点名文件。
     *
     * <p>守护 {@code computeExpected} 的产物读取兜底：读不到还按 0 条算会让一致性判定失真。
     */
    @Test
    void computeExpectedShouldReportUnreadableEmbeddingArtifact() {
        when(fileResultDbService.listByKb(1L)).thenReturn(List.of(fileRow(10L)));
        WbEmbeddingSet embed = embedRow();
        when(chunkSetDbService.listByFileResultIds(List.of(10L)))
                .thenReturn(List.of(chunkRow(1L, "chunk-hybrid-v1")));
        when(embeddingSetDbService.listByFileResultIds(List.of(10L))).thenReturn(List.of(embed));
        when(lineageResolver.resolvePreprocessStrategy(100L)).thenReturn("preproc-default-v1");
        when(indexRowAssembler.readEmbeddingSet(ObjectRef.ofEmbeddingSet(embed))).thenReturn(null);
        ComboSnapshot combo = ComboSnapshot.of("preproc-default-v1", "chunk-hybrid-v1", "embed-default-v1");

        IndexComboReconciler.ComboExpectation expectation = reconciler.computeExpected(1L, combo);

        assertFalse(expectation.complete());
        assertTrue(expectation.gap().contains("向量产物读取失败"), expectation.gap());
    }

    /**
     * 只统计 SUCCESS/CACHED 记录：失败与跳过记录不进片集合与向量数。
     *
     * <p>守护 {@code computeExpected} 的记录状态过滤：把失败记录算进去会让对账虚高。
     */
    @Test
    void computeExpectedShouldSkipFailedRecords() {
        when(fileResultDbService.listByKb(1L)).thenReturn(List.of(fileRow(10L)));
        WbEmbeddingSet embed = embedRow();
        embed.setDimension(1024);
        when(chunkSetDbService.listByFileResultIds(List.of(10L)))
                .thenReturn(List.of(chunkRow(1L, "chunk-hybrid-v1")));
        when(embeddingSetDbService.listByFileResultIds(List.of(10L))).thenReturn(List.of(embed));
        when(lineageResolver.resolvePreprocessStrategy(100L)).thenReturn("preproc-default-v1");
        EmbeddingRecord success = record("chunk-1", "SUCCESS", List.of(1.0f, 2.0f), "投标保证金");
        EmbeddingRecord cached = record("chunk-2", "CACHED", List.of(3.0f, 4.0f), null);
        EmbeddingRecord failed = record("chunk-3", "FAILED", List.of(5.0f, 6.0f), "失败内容");
        EmbeddingRecord skipped = record("chunk-4", "SKIPPED", List.of(), "跳过内容");
        EmbeddingSet artifact = new EmbeddingSet();
        artifact.setRecords(List.of(success, cached, failed, skipped));
        when(indexRowAssembler.readEmbeddingSet(ObjectRef.ofEmbeddingSet(embed))).thenReturn(artifact);
        ComboSnapshot combo = ComboSnapshot.of("preproc-default-v1", "chunk-hybrid-v1", "embed-default-v1");

        IndexComboReconciler.ComboExpectation expectation = reconciler.computeExpected(1L, combo);

        assertTrue(expectation.complete());
        assertEquals(Set.of("chunk-1", "chunk-2"), expectation.chunkIds());
        assertEquals(2, expectation.vectorCount());
        assertEquals(List.of(1.0f, 2.0f), expectation.sampleVector());
        assertEquals("投标保证金", expectation.sampleKeyword());
    }

    /**
     * 两个文件的向量维度不一致：对账期望判维度不齐。
     *
     * <p>守护 {@code computeExpected} 的维度一致性判定：维度混用会被 Milvus 拒写。
     */
    @Test
    void computeExpectedShouldReportDimensionMismatch() {
        when(fileResultDbService.listByKb(1L)).thenReturn(List.of(fileRow(10L), fileRow(11L)));
        WbChunkSet chunk10 = chunkRow(1L, "chunk-hybrid-v1");
        WbChunkSet chunk11 = chunkRow(2L, "chunk-hybrid-v1");
        chunk11.setFileResultId(11L);
        WbEmbeddingSet embed10 = embedRow();
        embed10.setDimension(1024);
        WbEmbeddingSet embed11 = embedRow();
        embed11.setId(3L);
        embed11.setFileResultId(11L);
        embed11.setDimension(2048);
        when(chunkSetDbService.listByFileResultIds(List.of(10L, 11L)))
                .thenReturn(List.of(chunk10, chunk11));
        when(embeddingSetDbService.listByFileResultIds(List.of(10L, 11L)))
                .thenReturn(List.of(embed10, embed11));
        when(lineageResolver.resolvePreprocessStrategy(100L)).thenReturn("preproc-default-v1");
        EmbeddingSet artifact10 = new EmbeddingSet();
        artifact10.setRecords(List.of(record("chunk-1", "SUCCESS", List.of(1.0f), "文本一")));
        EmbeddingSet artifact11 = new EmbeddingSet();
        artifact11.setRecords(List.of(record("chunk-2", "SUCCESS", List.of(2.0f), "文本二")));
        when(indexRowAssembler.readEmbeddingSet(ObjectRef.ofEmbeddingSet(embed10))).thenReturn(artifact10);
        when(indexRowAssembler.readEmbeddingSet(ObjectRef.ofEmbeddingSet(embed11))).thenReturn(artifact11);
        ComboSnapshot combo = ComboSnapshot.of("preproc-default-v1", "chunk-hybrid-v1", "embed-default-v1");

        IndexComboReconciler.ComboExpectation expectation = reconciler.computeExpected(1L, combo);

        assertFalse(expectation.dimConsistent());
        assertTrue(expectation.dimError().contains("向量维度不一致"), expectation.dimError());
    }

    /**
     * 向量行映射同样按 id 取最新。
     *
     * <p>守护 {@code latestEmbedMap} 的取最新口径：取旧行会让对账读到过期产物。
     */
    @Test
    void latestEmbedMapShouldKeepLatestById() {
        WbEmbeddingSet older = embedRow();
        WbEmbeddingSet newer = embedRow();
        newer.setId(9L);
        when(embeddingSetDbService.listByFileResultIds(anyList())).thenReturn(List.of(older, newer));

        Map<String, WbEmbeddingSet> latest = reconciler.latestEmbedMap(List.of(10L));

        assertEquals(9L, latest.get("10#embed-default-v1").getId());
    }

    /** 文件结果行夹具 */
    private WbFileResult fileRow(Long id) {
        WbFileResult file = new WbFileResult();
        file.setId(id);
        file.setKnowledgeBaseId(1L);
        return file;
    }

    /** 向量记录夹具 */
    private EmbeddingRecord record(String chunkId, String status, List<Float> vector, String inputText) {
        EmbeddingRecord record = new EmbeddingRecord();
        record.setChunkId(chunkId);
        record.setStatus(status);
        record.setVector(vector);
        record.setInputText(inputText);
        return record;
    }
}
