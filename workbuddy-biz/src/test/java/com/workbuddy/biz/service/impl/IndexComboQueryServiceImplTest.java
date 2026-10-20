package com.workbuddy.biz.service.impl;

import com.workbuddy.biz.service.db.WbFileResultDbService;
import com.workbuddy.biz.service.support.IndexComboReconciler;
import com.workbuddy.biz.service.support.IndexLineageResolver;
import com.workbuddy.common.domain.entity.WbChunkSet;
import com.workbuddy.common.domain.entity.WbEmbeddingSet;
import com.workbuddy.common.dto.response.index.IndexComboVO;
import com.workbuddy.common.enums.index.IndexShape;
import com.workbuddy.worker.indexing.ComboSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.workbuddy.biz.service.impl.IndexSetTestFixtures.chunkRowOf;
import static com.workbuddy.biz.service.impl.IndexSetTestFixtures.embedRowOf;
import static com.workbuddy.biz.service.impl.IndexSetTestFixtures.file;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 组合查询单测：成员子集口径（按范围内有完整产物的文件返回组合预览）。
 */
class IndexComboQueryServiceImplTest {

    private WbFileResultDbService fileResultDbService;
    private IndexComboReconciler indexComboReconciler;
    private IndexLineageResolver lineageResolver;
    private IndexComboQueryServiceImpl service;

    @BeforeEach
    void setUp() {
        fileResultDbService = mock(WbFileResultDbService.class);
        indexComboReconciler = mock(IndexComboReconciler.class);
        lineageResolver = mock(IndexLineageResolver.class);
        service = new IndexComboQueryServiceImpl(fileResultDbService, indexComboReconciler);
    }

    /**
     * 组合列表：按成员子集返回组合与文件/向量数预览。
     *
     * <p>守护 {@code listCombos}：按全库交集返回即失败。
     */
    @Test
    void listCombosShouldReturnCombosWithMemberFiles() {
        // 文件 10 仅组合 A；文件 20 有 A/B 两套策略历史 → 组合按成员子集返回（非全库交集）
        when(fileResultDbService.listByKb(1L)).thenReturn(List.of(file(10L), file(20L)));
        Map<String, WbChunkSet> latestChunk = new LinkedHashMap<>();
        latestChunk.put("10#chunk-hybrid-v1", chunkRowOf(1L, 10L, "chunk-hybrid-v1", 100L));
        latestChunk.put("20#chunk-hybrid-v1", chunkRowOf(2L, 20L, "chunk-hybrid-v1", 100L));
        latestChunk.put("20#chunk-hybrid-v2", chunkRowOf(3L, 20L, "chunk-hybrid-v2", 101L));
        Map<String, WbEmbeddingSet> latestEmbed = new LinkedHashMap<>();
        latestEmbed.put("10#embed-default-v1", embedRowOf(2L, 10L, "embed-default-v1", 50));
        latestEmbed.put("20#embed-default-v1", embedRowOf(3L, 20L, "embed-default-v1", 30));
        latestEmbed.put("20#embed-default-v2", embedRowOf(4L, 20L, "embed-default-v2", 20));
        Map<String, Set<String>> preprocessByChunk = new LinkedHashMap<>();
        preprocessByChunk.put("chunk-hybrid-v1", new LinkedHashSet<>(List.of("preproc-default-v1")));
        preprocessByChunk.put("chunk-hybrid-v2", new LinkedHashSet<>(List.of("preproc-default-v2")));
        Set<String> embedStrategies = new LinkedHashSet<>(List.of("embed-default-v1", "embed-default-v2"));
        when(indexComboReconciler.catalog(List.of(10L, 20L)))
                .thenReturn(new IndexComboReconciler.ComboCatalog(
                        latestChunk, latestEmbed, preprocessByChunk, embedStrategies));
        when(lineageResolver.resolvePreprocessStrategy(100L)).thenReturn("preproc-default-v1");
        when(lineageResolver.resolvePreprocessStrategy(101L)).thenReturn("preproc-default-v2");
        stubComboProductsSelection();

        var combos = service.listCombos(1L, null);

        assertEquals(4, combos.size());
        IndexComboVO a = combos.stream()
                .filter(c -> "chunk-hybrid-v1".equals(c.getChunkStrategy())
                        && "embed-default-v1".equals(c.getEmbedStrategy()))
                .findFirst().orElseThrow();
        assertEquals("ALL", a.getFileScopeMode());
        assertEquals(IndexShape.FULL_VECTOR, a.getShape());
        assertEquals("preproc-default-v1", a.getStageStrategies().get("PREPROCESS"));
        assertTrue(a.isComplete());
        assertEquals(List.of(10L, 20L), a.getFileResultIds());
        assertEquals(2, a.getFileCount());
        assertEquals(80, a.getVectorCount());
        assertTrue(combos.stream().noneMatch(c -> c.getFileResultIds().isEmpty()));
    }

    /**
     * 组合列表：范围收窄到指定文件。
     *
     * <p>守护 {@code listCombos}：范围外的文件混入即失败。
     */
    @Test
    void listCombosShouldScopeMembersToGivenFiles() {
        // 范围收窄：仅文件 10 参与成员判定
        Map<String, WbChunkSet> latestChunk = new LinkedHashMap<>();
        latestChunk.put("10#chunk-hybrid-v1", chunkRowOf(1L, 10L, "chunk-hybrid-v1", 100L));
        Map<String, WbEmbeddingSet> latestEmbed = new LinkedHashMap<>();
        latestEmbed.put("10#embed-default-v1", embedRowOf(2L, 10L, "embed-default-v1", 50));
        Map<String, Set<String>> preprocessByChunk = new LinkedHashMap<>();
        preprocessByChunk.put("chunk-hybrid-v1", new LinkedHashSet<>(List.of("preproc-default-v1")));
        Set<String> embedStrategies = new LinkedHashSet<>(List.of("embed-default-v1"));
        when(indexComboReconciler.catalog(List.of(10L)))
                .thenReturn(new IndexComboReconciler.ComboCatalog(
                        latestChunk, latestEmbed, preprocessByChunk, embedStrategies));
        when(lineageResolver.resolvePreprocessStrategy(100L)).thenReturn("preproc-default-v1");
        stubComboProductsSelection();

        var combos = service.listCombos(1L, List.of(10L));

        assertEquals(1, combos.size());
        assertEquals(List.of(10L), combos.getFirst().getFileResultIds());
        assertEquals(1, combos.getFirst().getFileCount());
        assertEquals(50, combos.getFirst().getVectorCount());
    }

    /** selectComboProducts 桩：按（文件#策略）批次行还原真实口径——产物齐 + 切片血统匹配才 complete */
    private void stubComboProductsSelection() {
        when(indexComboReconciler.selectComboProducts(any(ComboSnapshot.class), anyLong(), any(), any()))
                .thenAnswer(inv -> {
                    ComboSnapshot combo = inv.getArgument(0);
                    Long fileId = inv.getArgument(1);
                    Map<String, WbChunkSet> lc = inv.getArgument(2);
                    Map<String, WbEmbeddingSet> le = inv.getArgument(3);
                    WbChunkSet cr = lc.get(fileId + "#" + combo.getChunkStrategy());
                    WbEmbeddingSet er = le.get(fileId + "#" + combo.getEmbedStrategy());
                    if (cr == null || er == null) {
                        return new IndexComboReconciler.ComboProducts(null, null, "缺产物");
                    }
                    String pre = lineageResolver.resolvePreprocessStrategy(cr.getUpstreamProductId());
                    if (!combo.getPreprocessStrategy().equals(pre)) {
                        return new IndexComboReconciler.ComboProducts(null, null, "血统不符");
                    }
                    return new IndexComboReconciler.ComboProducts(cr, er, null);
                });
    }
}
