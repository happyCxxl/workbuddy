package com.workbuddy.biz.service.impl;

import com.workbuddy.common.domain.entity.WbEmbeddingRecord;
import com.workbuddy.common.domain.entity.WbEmbeddingSet;
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.domain.entity.WbPipelineStepLog;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.dto.response.embed.EmbedDetailVO;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 向量化详情用例：任务态、子步骤、产物引用、集合与记录统计逐列下发；无任务时记录为空。
 *
 * <p>触发用例在 {@link EmbedControlTriggerTest}；装配与夹具数据见 {@link EmbedControlTestFixture}。
 */
@ExtendWith(MockitoExtension.class)
class EmbedControlDetailTest {

    private EmbedControlTestFixture fixture;

    private EmbedControlServiceImpl service;

    @BeforeEach
    void setUp() {
        fixture = new EmbedControlTestFixture();
        service = fixture.service();
    }

    /**
     * 向量化详情：任务态、子步骤、产物引用与集合/记录统计逐列下发。
     *
     * <p>守护 {@code embedDetail}：漏搬任一类即失败。
     */
    @Test
    void detailShouldAssembleSummaryAndRecords() {
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        WbPipelineTask task = new WbPipelineTask();
        task.setId(41L);
        task.setStage(PipelineStage.EMBED.name());
        task.setStatus(PipelineTaskStatus.FAILED.name());
        task.setErrorCode("EMBED_TIMEOUT");
        task.setErrorMsg("模型超时");
        task.setStartedAt(LocalDateTime.of(2026, 10, 9, 10, 0));
        task.setFinishedAt(LocalDateTime.of(2026, 10, 9, 10, 5));
        task.setProductId(50L);
        when(fixture.pipelineTaskDbService.getByFileResultIdAndStage(10L, PipelineStage.EMBED.name()))
                .thenReturn(task);
        WbPipelineProduct product = new WbPipelineProduct();
        product.setArtifactId("abc".repeat(16));
        product.setContentHash("sha-abc");
        product.setCapabilitySnapshot("{\"model\":\"text-embedding-v4\"}");
        // 产物行记下对象位置：详情据此取向量集合
        product.setStorageType(StorageType.MINIO.getCode());
        product.setStorageSourceId(EmbedControlTestFixture.SOURCE_ID);
        product.setBucket("artifacts");
        when(fixture.pipelineProductDbService.getById(50L)).thenReturn(product);
        WbPipelineStepLog step = new WbPipelineStepLog();
        step.setTaskId(41L);
        step.setStepName("encode");
        step.setStatus("SUCCESS");
        when(fixture.stepLogDbService.listByTaskId(41L)).thenReturn(List.of(step));
        WbEmbeddingSet set = new WbEmbeddingSet();
        set.setId(70L);
        set.setEmbeddingSetId("es-cs-1-embed-default-v1");
        set.setStrategyVersion("embed-default-v1");
        set.setModel("text-embedding-v4");
        set.setDimension(1024);
        set.setMetric("COSINE");
        set.setNormalized(true);
        set.setRecordCount(3);
        set.setCachedCount(1);
        set.setArtifactId("abc".repeat(16));
        set.setStorageType(StorageType.MINIO.getCode());
        set.setStorageSourceId(EmbedControlTestFixture.SOURCE_ID);
        set.setBucket("artifacts");
        when(fixture.embeddingSetDbService.getByArtifactId("abc".repeat(16))).thenReturn(set);
        WbEmbeddingRecord cached = new WbEmbeddingRecord();
        cached.setEmbeddingId("emb-0001");
        cached.setStatus("CACHED");
        cached.setCacheHit(true);
        WbEmbeddingRecord fresh = new WbEmbeddingRecord();
        fresh.setEmbeddingId("emb-0002");
        fresh.setStatus("SUCCESS");
        WbEmbeddingRecord skipped = new WbEmbeddingRecord();
        skipped.setEmbeddingId("emb-0003");
        skipped.setStatus("SKIPPED");
        when(fixture.embeddingRecordDbService.listByEmbeddingSetId(70L))
                .thenReturn(List.of(cached, fresh, skipped));

        EmbedDetailVO detail = service.embedDetail(10L, null);

        assertEquals(10L, detail.getFileResultId());
        assertEquals(41L, detail.getTaskId());
        assertEquals(PipelineStage.EMBED.name(), detail.getStage());
        assertEquals(PipelineTaskStatus.FAILED.name(), detail.getTaskStatus());
        assertEquals("EMBED_TIMEOUT", detail.getErrorCode());
        assertEquals("模型超时", detail.getErrorMsg());
        assertNotNull(detail.getStartedAt());
        assertNotNull(detail.getFinishedAt());
        assertEquals(1, detail.getSteps().size());
        // 产物引用与能力快照随详情下发（历史任务也能看到自己的那次配置）
        assertEquals("abc".repeat(16), detail.getArtifactId());
        assertEquals("sha-abc", detail.getContentHash());
        assertEquals("{\"model\":\"text-embedding-v4\"}", detail.getCapabilitySnapshot());
        assertNotNull(detail.getSummary());
        assertEquals(3, detail.getSummary().getRecordCount());
        assertEquals(1, detail.getSummary().getCachedCount());
        assertEquals(1, detail.getSummary().getSuccessCount());
        assertEquals(0, detail.getSummary().getFailedCount());
        assertEquals(1, detail.getSummary().getSkippedCount());
        assertEquals(3, detail.getRecords().size());
    }

    /**
     * 向量化详情：无任务时记录为空、不读集合。
     *
     * <p>守护 {@code embedDetail}：无任务仍读集合即失败。
     */
    @Test
    void detailWithoutTaskShouldReturnEmptyRecords() {
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        when(fixture.pipelineTaskDbService.getByFileResultIdAndStage(10L, PipelineStage.EMBED.name()))
                .thenReturn(null);

        EmbedDetailVO detail = service.embedDetail(10L, null);

        assertNotNull(detail.getRecords());
        assertTrue(detail.getRecords().isEmpty());
    }
}
