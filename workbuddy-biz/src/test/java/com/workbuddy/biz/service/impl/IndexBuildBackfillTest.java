package com.workbuddy.biz.service.impl;

import com.workbuddy.common.domain.entity.WbChunkSet;
import com.workbuddy.common.domain.entity.WbFileResult;
import com.workbuddy.common.domain.entity.WbIndexVersion;
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.domain.entity.WbPipelineStrategyVersion;
import com.workbuddy.common.enums.task.PipelineStage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.workbuddy.biz.service.impl.IndexSetTestFixtures.comboAll;
import static com.workbuddy.biz.service.impl.IndexSetTestFixtures.file;
import static com.workbuddy.biz.service.impl.IndexSetTestFixtures.indexSet;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 拓扑补齐单测：按在线组合从缺失的最上游环节投递，已齐/策略缺失/上游缺失时中止。
 */
class IndexBuildBackfillTest {

    private IndexBuildFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new IndexBuildFixture();
    }

    /**
     * 产物组合 ≠ 在线组合：按在线组合拓扑补齐。
     *
     * <p>守护 {@code onFileProductsReady}：文件被丢下即失败。
     */
    @Test
    void onFileProductsReadyWhenProductComboDiffersFromOnlineShouldBackfillByTopology() {
        // 竞态：切到 v2 后，文件按旧组合（v2 组合）完成 → 规则一追加进 v2 集合，规则三按在线组合（v1）补齐
        WbFileResult file = file(10L);
        when(fixture.fileResultDbService.getById(10L)).thenReturn(file);
        when(fixture.knowledgeBaseDbService.getById(1L)).thenReturn(IndexSetTestFixtures.kbOn());
        fixture.stubProductCombo(file, "preproc-default-v2", "chunk-hybrid-v2", "embed-default-v2");
        when(fixture.indexSetDbService.getOrCreateByKb(1L)).thenReturn(indexSet());
        when(fixture.indexVersionDbService.listByIndexSetId(5L)).thenReturn(List.of());
        when(fixture.indexVersionDbService.nextVersionNo(5L)).thenReturn("v1");
        when(fixture.indexVersionDbService.save(any(WbIndexVersion.class))).thenAnswer(inv -> {
            inv.<WbIndexVersion>getArgument(0).setId(100L);
            return true;
        });
        when(fixture.milvusIndexPort.listChunkIds("kb_1_v1", 10L)).thenReturn(List.of("chunk-1"));
        fixture.stubOnlineCombo("v0", comboAll());
        when(fixture.indexComboService.resolveBoundCombo(1L))
                .thenReturn(com.workbuddy.worker.indexing.ComboSnapshot.of(
                        "preproc-default-v2", "chunk-hybrid-v2", "embed-default-v2"));
        when(fixture.chunkSetDbService.listByFileResultIds(List.of(10L))).thenReturn(List.of());
        WbPipelineStrategyVersion strategyRow = new WbPipelineStrategyVersion();
        strategyRow.setId(77L);
        when(fixture.strategyVersionDbService.getByTypeAndNameAndVersion("PREPROCESS", "preproc-default", "v1"))
                .thenReturn(strategyRow);
        when(fixture.indexSetDbService.getByKb(1L)).thenReturn(indexSet());

        fixture.service.onFileProductsReady(10L);

        // 规则一：追加进产物组合集合（v1 编号）
        verify(fixture.milvusIndexPort).append(eq("kb_1_v1"), anyList());
        // 规则三：按在线组合拓扑重放（缺预处理 → 投递预处理）
        verify(fixture.preprocessControlService).preprocess(10L, 77L, null);
        verify(fixture.indexSetDbService, never()).updatePublishedVersion(anyLong(), anyLong());
    }

    /**
     * 无匹配切片的文件：按在线组合投递预处理补齐。
     *
     * <p>守护 {@code backfillFile} 的预处理投递分支：谓词写反会把该补的文件跳过。
     */
    @Test
    void backfillShouldEnqueuePreprocessWhenNoMatchingChunk() {
        when(fixture.chunkSetDbService.listByFileResultIds(List.of(10L))).thenReturn(List.of());
        WbPipelineStrategyVersion preprocessStrategy = new WbPipelineStrategyVersion();
        preprocessStrategy.setId(77L);
        when(fixture.strategyVersionDbService.getByTypeAndNameAndVersion("PREPROCESS", "preproc-default", "v1"))
                .thenReturn(preprocessStrategy);

        fixture.buildSupport.backfillFile(10L, comboAll());

        verify(fixture.preprocessControlService).preprocess(10L, 77L, null);
        verify(fixture.chunkControlService, never()).chunk(anyLong(), anyLong(), any());
    }

    /**
     * 拓扑补齐：已有目标预处理链但缺目标切片 → 投递切片并显式绑定上游预处理产物。
     *
     * <p>守护 {@code backfillFile} 的切片补齐分支：漏带 upstreamProductId 会混用新旧预处理口径。
     */
    @Test
    void backfillShouldEnqueueChunkWithMatchedUpstreamProduct() {
        // 已有一条目标预处理口径的切片（策略不同） → 缺目标切片
        WbChunkSet existing = IndexSetTestFixtures.chunkRowOf(1L, 10L, "chunk-hybrid-v2", 100L);
        when(fixture.chunkSetDbService.listByFileResultIds(List.of(10L))).thenReturn(List.of(existing));
        when(fixture.lineageResolver.resolvePreprocessStrategy(100L)).thenReturn("preproc-default-v1");
        WbPipelineStrategyVersion chunkStrategy = new WbPipelineStrategyVersion();
        chunkStrategy.setId(88L);
        when(fixture.strategyVersionDbService.getByTypeAndNameAndVersion("CHUNK", "chunk-hybrid", "v1"))
                .thenReturn(chunkStrategy);
        // 预处理产物快照：一条坏快照 + 一条匹配快照（坏快照只告警不中断）
        WbPipelineProduct broken = new WbPipelineProduct();
        broken.setId(500L);
        broken.setStage(PipelineStage.PREPROCESS.name());
        broken.setCapabilitySnapshot("not-json");
        WbPipelineProduct matched = new WbPipelineProduct();
        matched.setId(501L);
        matched.setStage(PipelineStage.PREPROCESS.name());
        matched.setCapabilitySnapshot("{\"type\":\"PREPROCESS\",\"name\":\"preproc-default\",\"version\":\"v1\"}");
        when(fixture.productDbService.listByFileResultId(10L)).thenReturn(List.of(broken, matched));

        fixture.buildSupport.backfillFile(10L, comboAll());

        verify(fixture.chunkControlService).chunk(10L, 88L, 501L);
        verify(fixture.preprocessControlService, never()).preprocess(anyLong(), anyLong(), any());
    }

    /**
     * 拓扑补齐：切片产物已齐（目标策略 + 命中血缘）→ 不再投递，等手动向量化。
     *
     * <p>守护 {@code backfillFile} 的「已齐」分支：重复投递会撞执行键、白跑任务。
     */
    @Test
    void backfillShouldSkipWhenTargetChunkAlreadyExists() {
        WbChunkSet matchedChunk = IndexSetTestFixtures.chunkRowOf(1L, 10L, "chunk-hybrid-v1", 100L);
        when(fixture.chunkSetDbService.listByFileResultIds(List.of(10L))).thenReturn(List.of(matchedChunk));
        when(fixture.lineageResolver.resolvePreprocessStrategy(100L)).thenReturn("preproc-default-v1");
        // 对抗性夹具（宽松）：若「已齐」判定被写反，补齐分支会真的走到投递，用 verify 抓这段差异
        WbPipelineStrategyVersion chunkStrategy = new WbPipelineStrategyVersion();
        chunkStrategy.setId(88L);
        lenient().when(fixture.strategyVersionDbService.getByTypeAndNameAndVersion("CHUNK", "chunk-hybrid", "v1"))
                .thenReturn(chunkStrategy);
        WbPipelineProduct preprocessProduct = new WbPipelineProduct();
        preprocessProduct.setId(501L);
        preprocessProduct.setStage(PipelineStage.PREPROCESS.name());
        preprocessProduct.setCapabilitySnapshot("{\"name\":\"preproc-default\",\"version\":\"v1\"}");
        lenient().when(fixture.productDbService.listByFileResultId(10L)).thenReturn(List.of(preprocessProduct));

        fixture.buildSupport.backfillFile(10L, comboAll());

        verify(fixture.preprocessControlService, never()).preprocess(anyLong(), anyLong(), any());
        verify(fixture.chunkControlService, never()).chunk(anyLong(), anyLong(), any());
    }

    /**
     * 拓扑补齐中止：目标预处理策略行不存在 / 目标切片策略行不存在 / 上游预处理产物缺失。
     *
     * <p>守护 {@code backfillFile} 的三处中止：策略行或上游产物缺失时投递出去的任务必然失败。
     */
    @Test
    void backfillShouldStopWhenStrategyOrUpstreamMissing() {
        // ① 无任何切片 → 走预处理投递；目标策略行不存在 → 中止
        when(fixture.chunkSetDbService.listByFileResultIds(List.of(10L))).thenReturn(List.of());
        when(fixture.strategyVersionDbService.getByTypeAndNameAndVersion("PREPROCESS", "preproc-default", "v1"))
                .thenReturn(null);

        fixture.buildSupport.backfillFile(10L, comboAll());

        verify(fixture.preprocessControlService, never()).preprocess(anyLong(), anyLong(), any());
        verify(fixture.chunkControlService, never()).chunk(anyLong(), anyLong(), any());
    }

    /**
     * 拓扑补齐中止：预处理口径已命中但目标切片策略行不存在。
     *
     * <p>守护 {@code backfillFile} 的切片策略缺失分支：投出去的切片任务会因策略不存在失败。
     */
    @Test
    void backfillShouldStopWhenChunkStrategyMissing() {
        WbChunkSet existing = IndexSetTestFixtures.chunkRowOf(1L, 10L, "chunk-hybrid-v2", 100L);
        when(fixture.chunkSetDbService.listByFileResultIds(List.of(10L))).thenReturn(List.of(existing));
        when(fixture.lineageResolver.resolvePreprocessStrategy(100L)).thenReturn("preproc-default-v1");
        when(fixture.strategyVersionDbService.getByTypeAndNameAndVersion("CHUNK", "chunk-hybrid", "v1"))
                .thenReturn(null);

        fixture.buildSupport.backfillFile(10L, comboAll());

        verify(fixture.chunkControlService, never()).chunk(anyLong(), anyLong(), any());
        verify(fixture.preprocessControlService, never()).preprocess(anyLong(), anyLong(), any());
    }

    /**
     * 已有切片但血统不是目标预处理：按目标预处理投递补齐（不能因为"有切片"就跳过）。
     *
     * <p>守护 {@code backfillFile} 的预处理谓词：判定写成恒假会漏投，写成恒真会跳过必补环节。
     */
    @Test
    void backfillShouldDispatchPreprocessWhenExistingChunkHasOtherLineage() {
        WbChunkSet chunk = IndexSetTestFixtures.chunkRowOf(1L, 10L, "chunk-hybrid-v1", 100L);
        when(fixture.chunkSetDbService.listByFileResultIds(List.of(10L))).thenReturn(List.of(chunk));
        when(fixture.lineageResolver.resolvePreprocessStrategy(100L)).thenReturn("preproc-legacy-v1");
        WbPipelineStrategyVersion preprocessStrategy = new WbPipelineStrategyVersion();
        preprocessStrategy.setId(77L);
        lenient().when(fixture.strategyVersionDbService.getByTypeAndNameAndVersion(
                "PREPROCESS", "preproc-default", "v1")).thenReturn(preprocessStrategy);
        WbPipelineProduct preprocessProduct = new WbPipelineProduct();
        preprocessProduct.setId(501L);
        preprocessProduct.setStage(PipelineStage.PREPROCESS.name());
        preprocessProduct.setCapabilitySnapshot("{\"name\":\"preproc-default\",\"version\":\"v1\"}");
        lenient().when(fixture.productDbService.listByFileResultId(10L)).thenReturn(List.of(preprocessProduct));

        fixture.buildSupport.backfillFile(10L, comboAll());

        verify(fixture.preprocessControlService).preprocess(10L, 77L, null);
        verify(fixture.chunkControlService, never()).chunk(anyLong(), anyLong(), any());
    }

    /**
     * 有切片产物但取不到匹配的上游预处理产物：补齐中止，不投递指向 0 号产物的任务。
     *
     * <p>守护 {@code preprocessProductIdOf} 的无匹配返回：返回 0 会让下游拿 0 当上游。
     */
    @Test
    void backfillShouldStopWhenNoMatchingPreprocessProduct() {
        WbChunkSet existing = IndexSetTestFixtures.chunkRowOf(1L, 10L, "chunk-hybrid-v2", 100L);
        when(fixture.chunkSetDbService.listByFileResultIds(List.of(10L))).thenReturn(List.of(existing));
        when(fixture.lineageResolver.resolvePreprocessStrategy(100L)).thenReturn("preproc-default-v1");
        WbPipelineStrategyVersion chunkStrategy = new WbPipelineStrategyVersion();
        chunkStrategy.setId(88L);
        when(fixture.strategyVersionDbService.getByTypeAndNameAndVersion("CHUNK", "chunk-hybrid", "v1"))
                .thenReturn(chunkStrategy);
        // 产物行只有 CHUNK 环节，取不到匹配的预处理产物
        WbPipelineProduct chunkProduct = new WbPipelineProduct();
        chunkProduct.setId(600L);
        chunkProduct.setStage(PipelineStage.CHUNK.name());
        when(fixture.productDbService.listByFileResultId(10L)).thenReturn(List.of(chunkProduct));

        fixture.buildSupport.backfillFile(10L, comboAll());

        verify(fixture.chunkControlService, never()).chunk(anyLong(), anyLong(), any());
    }
}
