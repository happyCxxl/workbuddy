package com.workbuddy.biz.service.impl;

import com.workbuddy.common.domain.entity.WbChunkSet;
import com.workbuddy.common.domain.entity.WbEmbeddingSet;
import com.workbuddy.common.domain.entity.WbIndexSet;
import com.workbuddy.common.domain.entity.WbIndexVersion;
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.domain.entity.WbPipelineStrategyVersion;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.enums.index.IndexVersionStatus;
import com.workbuddy.common.enums.task.PipelineStage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static com.workbuddy.biz.service.impl.IndexSetTestFixtures.file;
import static com.workbuddy.biz.service.impl.IndexSetTestFixtures.indexSet;
import static com.workbuddy.biz.service.impl.IndexSetTestFixtures.versionRow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回退补齐单测：无差异投递 COMPENSATE 构建，有差异按目标组合从缺失环节重跑。
 */
class IndexBuildCompensateTest {

    private IndexBuildFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new IndexBuildFixture();
    }

    /**
     * 回退补齐（无差异）：直接投递 COMPENSATE 构建并记录任务。
     *
     * <p>守护 {@code compensate} 的无差异分支：漏补偿构建即失败。
     */
    @Test
    void compensateWhenNoDiffShouldEnqueueCompensateBuild() {
        WbIndexVersion target = versionRow(15L, "v1", IndexVersionStatus.RETIRED.name());
        WbIndexSet set = indexSet();
        set.setCurrentPublishedVersionId(10L);
        when(fixture.fileResultDbService.listByKb(1L)).thenReturn(List.of(file(10L)));
        WbChunkSet chunk = IndexSetTestFixtures.chunkRowOf(1L, 10L, "chunk-hybrid-v1", 100L);
        when(fixture.chunkSetDbService.listByFileResultIds(List.of(10L))).thenReturn(List.of(chunk));
        WbEmbeddingSet embed = new WbEmbeddingSet();
        embed.setId(2L);
        embed.setFileResultId(10L);
        embed.setStrategyVersion("embed-default-v1");
        when(fixture.embeddingSetDbService.listByFileResultIds(List.of(10L))).thenReturn(List.of(embed));
        when(fixture.lineageResolver.resolvePreprocessStrategy(100L)).thenReturn("preproc-default-v1");
        when(fixture.indexSetDbService.getOrCreateByKb(1L)).thenReturn(indexSet());
        when(fixture.indexVersionDbService.listByIndexSetId(5L)).thenReturn(List.of());
        when(fixture.indexVersionDbService.nextVersionNo(5L)).thenReturn("v3");
        fixture.stubSavedVersion(101L);
        fixture.stubTaskSave(201L);

        fixture.service.compensate(set, target);

        ArgumentCaptor<WbPipelineTask> taskCaptor = ArgumentCaptor.forClass(WbPipelineTask.class);
        verify(fixture.pipelineTaskDbService).save(taskCaptor.capture());
        assertTrue(taskCaptor.getValue().getStrategySnapshot().contains("\"COMPENSATE\""),
                taskCaptor.getValue().getStrategySnapshot());
        verify(fixture.taskQueue).enqueue(201L);
        verify(fixture.chunkControlService, never()).chunk(anyLong(), anyLong(), any());
    }

    /**
     * 回退补齐：切片策略不符而向量齐备 → 仍按缺口投递切片。
     *
     * <p>守护 {@code compensate} 的切片谓词：判定恒真会把该补的文件当成已齐、只做补偿构建。
     */
    @Test
    void compensateShouldDispatchChunkWhenChunkStrategyMismatches() {
        WbIndexVersion target = versionRow(15L, "v1", IndexVersionStatus.RETIRED.name());
        WbIndexSet set = indexSet();
        set.setCurrentPublishedVersionId(10L);
        when(fixture.fileResultDbService.listByKb(1L)).thenReturn(List.of(file(10L)));
        WbChunkSet chunk = IndexSetTestFixtures.chunkRowOf(1L, 10L, "chunk-legacy-v1", 100L);
        when(fixture.chunkSetDbService.listByFileResultIds(List.of(10L))).thenReturn(List.of(chunk));
        when(fixture.lineageResolver.resolvePreprocessStrategy(100L)).thenReturn("preproc-default-v1");
        WbEmbeddingSet embed = new WbEmbeddingSet();
        embed.setId(2L);
        embed.setFileResultId(10L);
        embed.setStrategyVersion("embed-default-v1");
        when(fixture.embeddingSetDbService.listByFileResultIds(List.of(10L))).thenReturn(List.of(embed));
        WbPipelineStrategyVersion chunkStrategy = new WbPipelineStrategyVersion();
        chunkStrategy.setId(88L);
        when(fixture.strategyVersionDbService.getByTypeAndNameAndVersion("CHUNK", "chunk-hybrid", "v1"))
                .thenReturn(chunkStrategy);
        WbPipelineProduct preprocessProduct = new WbPipelineProduct();
        preprocessProduct.setId(501L);
        preprocessProduct.setStage(PipelineStage.PREPROCESS.name());
        preprocessProduct.setCapabilitySnapshot("{\"name\":\"preproc-default\",\"version\":\"v1\"}");
        when(fixture.productDbService.listByFileResultId(10L)).thenReturn(List.of(preprocessProduct));

        fixture.service.compensate(set, target);

        verify(fixture.chunkControlService).chunk(10L, 88L, 501L);
        verify(fixture.pipelineTaskDbService, never()).save(any());
    }
}
