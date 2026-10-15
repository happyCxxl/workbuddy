package com.workbuddy.biz.service.support;

import com.workbuddy.biz.service.db.KbChunkSetDbService;
import com.workbuddy.biz.service.db.KbEmbeddingSetDbService;
import com.workbuddy.biz.service.db.KbFileResultDbService;
import com.workbuddy.common.domain.embed.EmbeddingRecord;
import com.workbuddy.common.domain.embed.EmbeddingSet;
import com.workbuddy.common.domain.entity.KbChunkSet;
import com.workbuddy.common.domain.entity.KbEmbeddingSet;
import com.workbuddy.common.domain.entity.KbFileResult;
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
 *
 * @author cxxl
 */
@ExtendWith(MockitoExtension.class)
class IndexComboReconcilerTest {

    /** 产物所在的数据源 */
    private static final Long SOURCE_ID = 1L;

    @Mock
    private KbFileResultDbService fileResultDbService;
    @Mock
    private KbChunkSetDbService chunkSetDbService;
    @Mock
    private KbEmbeddingSetDbService embeddingSetDbService;
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

    private KbChunkSet chunkRow(Long id, String strategy) {
        KbChunkSet row = new KbChunkSet();
        row.setId(id);
        row.setFileResultId(10L);
        row.setChunkStrategyVersion(strategy);
        row.setUpstreamProductId(100L);
        return row;
    }

    private KbEmbeddingSet embedRow() {
        KbEmbeddingSet row = new KbEmbeddingSet();
        row.setId(2L);
        row.setFileResultId(10L);
        row.setStrategyVersion("embed-default-v1");
        // 读向量产物按记录里的数据源与桶名定位：行对象必须带上这两段
        row.setStorageType(StorageType.MINIO.getCode());
        row.setStorageSourceId(SOURCE_ID);
        row.setBucket("artifacts");
        return row;
    }

    @Test
    void computeExpectedShouldRejectLegacyComboWithoutStageStrategies() {
        ComboSnapshot legacy = new ComboSnapshot();

        IndexComboReconciler.ComboExpectation expectation = reconciler.computeExpected(1L, legacy);

        assertFalse(expectation.complete());
        assertEquals("组合缺失环节策略维度（需废弃重灌）", expectation.gap());
        verifyNoInteractions(fileResultDbService);
    }

    @Test
    void computeExpectedShouldRejectPartialComboMissingPreprocess() {
        ComboSnapshot partial = ComboSnapshot.of(null, "chunk-hybrid-v1", "embed-default-v1");

        IndexComboReconciler.ComboExpectation expectation = reconciler.computeExpected(1L, partial);

        assertFalse(expectation.complete());
        assertEquals("组合缺失环节策略维度（需废弃重灌）", expectation.gap());
        verifyNoInteractions(fileResultDbService);
    }

    @Test
    void selectComboProductsShouldReturnRowsWhenLineageMatches() {
        KbChunkSet chunk = chunkRow(1L, "chunk-hybrid-v1");
        KbEmbeddingSet embed = embedRow();
        when(lineageResolver.resolvePreprocessStrategy(100L)).thenReturn("preproc-default-v1");

        IndexComboReconciler.ComboProducts products = reconciler.selectComboProducts(
                ComboSnapshot.of("preproc-default-v1", "chunk-hybrid-v1", "embed-default-v1"),
                10L, Map.of("10#chunk-hybrid-v1", chunk), Map.of("10#embed-default-v1", embed));

        assertTrue(products.complete());
        assertEquals(chunk, products.chunkRow());
        assertEquals(embed, products.embedRow());
    }

    @Test
    void selectComboProductsShouldReportGapWhenProductMissing() {
        IndexComboReconciler.ComboProducts products = reconciler.selectComboProducts(
                ComboSnapshot.of("preproc-default-v1", "chunk-hybrid-v1", "embed-default-v1"),
                10L, Map.of(), Map.of());

        assertFalse(products.complete());
        assertTrue(products.gap().contains("缺少组合产物"), products.gap());
    }

    @Test
    void selectComboProductsShouldReportGapWhenLineageMismatch() {
        KbChunkSet chunk = chunkRow(1L, "chunk-hybrid-v1");
        when(lineageResolver.resolvePreprocessStrategy(100L)).thenReturn("preproc-other-v2");

        IndexComboReconciler.ComboProducts products = reconciler.selectComboProducts(
                ComboSnapshot.of("preproc-default-v1", "chunk-hybrid-v1", "embed-default-v1"),
                10L, Map.of("10#chunk-hybrid-v1", chunk),
                Map.of("10#embed-default-v1", embedRow()));

        assertFalse(products.complete());
        assertTrue(products.gap().contains("血统与组合预处理策略不符"), products.gap());
    }

    @Test
    void latestChunkMapShouldKeepLatestById() {
        when(chunkSetDbService.listByFileResultIds(anyList())).thenReturn(List.of(
                chunkRow(1L, "chunk-a-v1"), chunkRow(3L, "chunk-a-v1")));

        Map<String, KbChunkSet> latest = reconciler.latestChunkMap(List.of(10L));

        assertEquals(3L, latest.get("10#chunk-a-v1").getId());
    }

    @Test
    void computeExpectedShouldRespectListScope() {
        KbFileResult file10 = new KbFileResult();
        file10.setId(10L);
        file10.setKnowledgeBaseId(1L);
        KbFileResult file11 = new KbFileResult();
        file11.setId(11L);
        file11.setKnowledgeBaseId(1L);
        when(fileResultDbService.listByKb(1L)).thenReturn(List.of(file10, file11));
        KbChunkSet chunk = chunkRow(1L, "chunk-hybrid-v1");
        KbEmbeddingSet embed = embedRow();
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
}
