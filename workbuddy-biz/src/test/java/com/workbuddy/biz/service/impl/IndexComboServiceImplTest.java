package com.workbuddy.biz.service.impl;

import com.workbuddy.biz.service.db.KbChunkSetDbService;
import com.workbuddy.biz.service.db.KbEmbeddingSetDbService;
import com.workbuddy.biz.service.db.KbFileResultDbService;
import com.workbuddy.biz.service.db.KbPipelineStrategyVersionDbService;
import com.workbuddy.biz.service.db.KbStrategyBindingDbService;
import com.workbuddy.biz.service.db.KnowledgeBaseDbService;
import com.workbuddy.biz.service.support.IndexComboReconciler;
import com.workbuddy.biz.service.support.IndexLineageResolver;
import com.workbuddy.biz.service.support.IndexRowAssembler;
import com.workbuddy.common.domain.entity.KbChunkSet;
import com.workbuddy.common.domain.entity.KbEmbeddingSet;
import com.workbuddy.common.domain.entity.KbFileResult;
import com.workbuddy.common.domain.entity.KbPipelineStrategyVersion;
import com.workbuddy.common.domain.entity.KbStrategyBinding;
import com.workbuddy.common.domain.entity.KnowledgeBase;
import com.workbuddy.common.enums.index.IndexShape;
import com.workbuddy.worker.indexing.ComboSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 索引组合口径服务单测（范围收窄枚举）：
 * 绑定单组合（预处理+切片+向量化三环节）/ 枚举完整组合（三维血缘匹配）/ 血统缺失排除 / 开关分发
 * / 指定文件范围枚举（完整性判定复用 IndexComboReconciler.selectComboProducts 单一口径）。
 *
 * @author cxxl
 */
@ExtendWith(MockitoExtension.class)
class IndexComboServiceImplTest {

    @Mock
    private KnowledgeBaseDbService knowledgeBaseDbService;
    @Mock
    private KbStrategyBindingDbService strategyBindingDbService;
    @Mock
    private KbPipelineStrategyVersionDbService strategyVersionDbService;
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

    private IndexComboServiceImpl service;

    @BeforeEach
    void setUp() {
        // 真实对账器：枚举的完整性判定复用其 selectComboProducts 单一口径
        IndexComboReconciler reconciler = new IndexComboReconciler(fileResultDbService, chunkSetDbService,
                embeddingSetDbService, lineageResolver, indexRowAssembler);
        service = new IndexComboServiceImpl(knowledgeBaseDbService, strategyBindingDbService,
                strategyVersionDbService, fileResultDbService, reconciler);
    }

    private KbFileResult file(Long id) {
        KbFileResult f = new KbFileResult();
        f.setId(id);
        f.setKnowledgeBaseId(2L);
        return f;
    }

    private KbChunkSet chunkSet(Long id, Long fileId, String strategy, Long upstreamProductId) {
        KbChunkSet s = new KbChunkSet();
        s.setId(id);
        s.setFileResultId(fileId);
        s.setChunkStrategyVersion(strategy);
        s.setUpstreamProductId(upstreamProductId);
        return s;
    }

    private KbEmbeddingSet embedSet(Long id, Long fileId, String strategy) {
        KbEmbeddingSet s = new KbEmbeddingSet();
        s.setId(id);
        s.setFileResultId(fileId);
        s.setStrategyVersion(strategy);
        return s;
    }

    private KbPipelineStrategyVersion version(Long id, String name) {
        KbPipelineStrategyVersion v = new KbPipelineStrategyVersion();
        v.setId(id);
        v.setName(name);
        v.setVersion("v1");
        return v;
    }

    private KbStrategyBinding binding(String type, Long versionId) {
        KbStrategyBinding b = new KbStrategyBinding();
        b.setStrategyType(type);
        b.setStrategyVersionId(versionId);
        return b;
    }

    @Test
    void resolveBoundComboShouldBuildFromBindings() {
        when(strategyBindingDbService.getByKbAndType(2L, "PREPROCESS")).thenReturn(binding("PREPROCESS", 55L));
        when(strategyBindingDbService.getByKbAndType(2L, "CHUNK")).thenReturn(binding("CHUNK", 66L));
        when(strategyBindingDbService.getByKbAndType(2L, "EMBED")).thenReturn(binding("EMBED", 88L));
        when(strategyVersionDbService.getById(55L)).thenReturn(version(55L, "preproc-default"));
        when(strategyVersionDbService.getById(66L)).thenReturn(version(66L, "chunk-hybrid"));
        when(strategyVersionDbService.getById(88L)).thenReturn(version(88L, "embed-default"));

        ComboSnapshot combo = service.resolveBoundCombo(2L);

        assertEquals("preproc-default-v1", combo.getPreprocessStrategy());
        assertEquals("chunk-hybrid-v1", combo.getChunkStrategy());
        assertEquals("embed-default-v1", combo.getEmbedStrategy());
        assertEquals(3, combo.getStageStrategies().size());
        assertEquals("ALL", combo.getFileScopeMode());
        assertEquals(IndexShape.FULL_VECTOR, combo.getShape());
    }

    @Test
    void resolveBoundComboShouldReturnNullWhenAnyBindingMissing() {
        when(strategyBindingDbService.getByKbAndType(2L, "PREPROCESS")).thenReturn(binding("PREPROCESS", 55L));
        when(strategyBindingDbService.getByKbAndType(2L, "CHUNK")).thenReturn(null);
        when(strategyBindingDbService.getByKbAndType(2L, "EMBED")).thenReturn(binding("EMBED", 88L));

        assertNull(service.resolveBoundCombo(2L));
    }

    @Test
    void enumerateCombosShouldExcludeIncompleteCombos() {
        when(fileResultDbService.listByKb(2L)).thenReturn(List.of(file(10L), file(11L)));
        when(chunkSetDbService.listByFileResultIds(List.of(10L, 11L))).thenReturn(List.of(
                chunkSet(1L, 10L, "chunk-a-v1", 100L), chunkSet(2L, 10L, "chunk-b-v1", 200L),
                chunkSet(3L, 11L, "chunk-a-v1", 300L))); // 文件 11 无 chunk-b
        when(embeddingSetDbService.listByFileResultIds(List.of(10L, 11L))).thenReturn(List.of(
                embedSet(1L, 10L, "embed-e1-v1"), embedSet(2L, 11L, "embed-e1-v1"),
                embedSet(3L, 10L, "embed-e2-v1"), embedSet(4L, 11L, "embed-e2-v1")));
        when(lineageResolver.resolvePreprocessStrategy(100L)).thenReturn("preproc-default-v1");
        when(lineageResolver.resolvePreprocessStrategy(200L)).thenReturn("preproc-default-v1");
        when(lineageResolver.resolvePreprocessStrategy(300L)).thenReturn("preproc-default-v1");

        List<ComboSnapshot> combos = service.enumerateCombos(2L);

        // chunk-b 组合不完整（文件 11 缺），只应枚举 chunk-a × {e1, e2}
        assertEquals(2, combos.size());
        assertTrue(combos.stream().allMatch(c -> "chunk-a-v1".equals(c.getChunkStrategy())));
        assertTrue(combos.stream().allMatch(c -> "preproc-default-v1".equals(c.getPreprocessStrategy())));
        assertTrue(combos.stream().anyMatch(c -> "embed-e1-v1".equals(c.getEmbedStrategy())));
        assertTrue(combos.stream().anyMatch(c -> "embed-e2-v1".equals(c.getEmbedStrategy())));
    }

    @Test
    void enumerateCombosShouldExcludeLineageMissingStrategy() {
        when(fileResultDbService.listByKb(2L)).thenReturn(List.of(file(10L), file(11L)));
        when(chunkSetDbService.listByFileResultIds(List.of(10L, 11L))).thenReturn(List.of(
                chunkSet(1L, 10L, "chunk-a-v1", 100L), chunkSet(2L, 11L, "chunk-a-v1", 300L)));
        when(embeddingSetDbService.listByFileResultIds(List.of(10L, 11L))).thenReturn(List.of(
                embedSet(1L, 10L, "embed-e1-v1"), embedSet(2L, 11L, "embed-e1-v1")));
        // 血统缺失：文件 11 的切片行上游预处理产物快照解析失败
        when(lineageResolver.resolvePreprocessStrategy(100L)).thenReturn("preproc-default-v1");
        when(lineageResolver.resolvePreprocessStrategy(300L)).thenReturn(null);

        assertTrue(service.enumerateCombos(2L).isEmpty());
    }

    @Test
    void enumerateCombosShouldReturnEmptyForNoFiles() {
        when(fileResultDbService.listByKb(2L)).thenReturn(List.of());

        assertTrue(service.enumerateCombos(2L).isEmpty());
    }

    @Test
    void enumerateCombosScopedShouldOnlyConsiderGivenFiles() {
        // 范围收窄：只枚举指定文件的组合（范围外文件缺失不影响完整性判定）
        when(chunkSetDbService.listByFileResultIds(List.of(10L))).thenReturn(List.of(
                chunkSet(1L, 10L, "chunk-a-v1", 100L), chunkSet(2L, 10L, "chunk-b-v1", 200L)));
        when(embeddingSetDbService.listByFileResultIds(List.of(10L))).thenReturn(List.of(
                embedSet(1L, 10L, "embed-e1-v1"), embedSet(3L, 10L, "embed-e2-v1")));
        when(lineageResolver.resolvePreprocessStrategy(100L)).thenReturn("preproc-default-v1");
        when(lineageResolver.resolvePreprocessStrategy(200L)).thenReturn("preproc-default-v1");

        List<ComboSnapshot> combos = service.enumerateCombos(List.of(10L));

        assertEquals(4, combos.size()); // 2 切片策略 × 2 向量策略
        assertTrue(combos.stream().allMatch(c -> "preproc-default-v1".equals(c.getPreprocessStrategy())));
    }

    @Test
    void enumerateCombosScopedShouldReturnEmptyForEmptyScope() {
        assertTrue(service.enumerateCombos(List.of()).isEmpty());
        assertTrue(service.enumerateCombos((List<Long>) null).isEmpty());
    }

    @Test
    void combosForShouldDispatchByBindingSwitch() {
        KnowledgeBase kbOn = new KnowledgeBase();
        kbOn.setId(2L);
        kbOn.setStrategyBindingEnabled(1);
        when(knowledgeBaseDbService.getById(2L)).thenReturn(kbOn);
        when(strategyBindingDbService.getByKbAndType(2L, "PREPROCESS")).thenReturn(binding("PREPROCESS", 55L));
        when(strategyBindingDbService.getByKbAndType(2L, "CHUNK")).thenReturn(null);
        when(strategyBindingDbService.getByKbAndType(2L, "EMBED")).thenReturn(binding("EMBED", 88L));

        // 绑定开但绑定不齐全 → 空列表
        assertTrue(service.combosFor(2L).isEmpty());

        KnowledgeBase kbOff = new KnowledgeBase();
        kbOff.setId(3L);
        kbOff.setStrategyBindingEnabled(0);
        when(knowledgeBaseDbService.getById(3L)).thenReturn(kbOff);
        when(fileResultDbService.listByKb(3L)).thenReturn(List.of());

        // 绑定关 → 走枚举（空文件 → 空列表）
        assertTrue(service.combosFor(3L).isEmpty());
    }
}
