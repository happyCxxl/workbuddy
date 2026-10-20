package com.workbuddy.biz.service.impl;

import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.domain.entity.WbPipelineStepLog;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.dto.response.structure.StructureDetailVO;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 结构组装详情用例：任务态、子步骤、摘要、大纲与告警冲突；空任务、错环节、存储位置与读取失败。
 *
 * <p>触发用例在 {@link StructureTriggerTest}；装配与夹具数据见 {@link StructureControlTestFixture}。
 */
@ExtendWith(MockitoExtension.class)
class StructureDetailTest {

    private StructureControlTestFixture fixture;

    private StructureControlServiceImpl service;

    @BeforeEach
    void setUp() {
        fixture = new StructureControlTestFixture();
        service = fixture.service();
    }

    /**
     * 详情：任务态、子步骤、摘要、大纲与告警冲突逐项下发。
     *
     * <p>守护 {@code structureDetail}：漏任一列即失败。
     */
    @Test
    void detailShouldAssembleTaskStepsSummaryAndOutline() {
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        WbPipelineTask task = new WbPipelineTask();
        task.setId(51L);
        task.setStage(PipelineStage.STRUCTURE.name());
        task.setStatus(PipelineTaskStatus.PARTIAL_SUCCESS.name());
        task.setProductId(50L);
        when(fixture.pipelineTaskDbService.getByFileResultIdAndStage(10L, PipelineStage.STRUCTURE.name()))
                .thenReturn(task);
        WbPipelineStepLog step = new WbPipelineStepLog();
        step.setStepName("结构组装");
        step.setStatus("SUCCESS");
        step.setWarningCount(1);
        step.setMatchedCount(5);
        step.setChangedCount(2);
        step.setAvgLen(400);
        when(fixture.stepLogDbService.listByTaskId(51L)).thenReturn(List.of(step));
        WbPipelineProduct product = new WbPipelineProduct();
        product.setArtifactId("def".repeat(16));
        product.setContentHash("def".repeat(16));
        // 产物行记下对象位置：详情读取按数据源与对象键定位
        product.setStorageType(StorageType.MINIO.getCode());
        product.setStorageSourceId(StructureControlTestFixture.SOURCE_ID);
        product.setBucket("artifacts");
        when(fixture.pipelineProductDbService.getById(50L)).thenReturn(product);
        String json = "{\"elements\":["
                + "{\"id\":\"h-1\",\"type\":\"TITLE\",\"text\":\"第一章\",\"level\":1},"
                + "{\"id\":\"n-1\",\"type\":\"PARAGRAPH\",\"text\":\"正文\",\"conflictStatus\":\"PRIMARY\"},"
                + "{\"id\":\"t-1\",\"type\":\"TABLE\",\"rows\":2,\"cols\":2,\"cells\":["
                + "{\"row\":0,\"col\":0,\"text\":\"A\",\"isHeader\":true},"
                + "{\"row\":0,\"col\":1,\"text\":\"B\",\"isHeader\":true}]}],"
                + "\"relations\":[{\"type\":\"PARENT_CHILD\",\"from\":\"h-1\",\"to\":\"n-1\"}],"
                + "\"quality\":{\"warnings\":[{\"code\":\"NOISE_PAGE\",\"level\":\"WARN\",\"message\":\"噪声页\"}],"
                + "\"conflicts\":[{\"primaryElementId\":\"n-1\",\"backupElementId\":\"n-2\",\"message\":\"无法裁决\"}]}}";
        when(fixture.fileStorage.getObject(ObjectRef.of(StructureControlTestFixture.SOURCE_ID,
                StorageType.MINIO.getCode(), "artifacts", "def".repeat(16))))
                .thenReturn(json.getBytes(StandardCharsets.UTF_8));

        StructureDetailVO detail = service.structureDetail(10L, null);

        assertEquals(51L, detail.getTaskId());
        assertEquals(PipelineTaskStatus.PARTIAL_SUCCESS.name(), detail.getStatus());
        assertEquals(1, detail.getSteps().size());
        assertEquals(1, detail.getSteps().getFirst().getWarningCount());
        assertEquals(5, detail.getSteps().getFirst().getMatchedCount());
        assertEquals(2, detail.getSteps().getFirst().getChangedCount());
        assertEquals(400, detail.getSteps().getFirst().getAvgLen());
        assertNotNull(detail.getSummary());
        assertEquals(3, detail.getSummary().getElementCount());
        assertEquals(1, detail.getSummary().getTitleCount());
        assertEquals(1, detail.getSummary().getRelationCount());
        assertEquals(1, detail.getSummary().getConflictCount());
        assertEquals(1, detail.getWarnings().size());
        assertEquals("n-1", detail.getConflicts().getFirst().getPrimaryElementId());
        assertEquals(3, detail.getOutline().size());
        assertEquals("TITLE", detail.getOutline().getFirst().getType());
        assertEquals("第一章", detail.getOutline().getFirst().getText());
        assertEquals("PRIMARY", detail.getOutline().get(1).getConflictStatus());
        assertEquals(2, detail.getOutline().get(2).getCells().size());
        assertTrue(detail.getOutline().get(2).getCells().getFirst().getIsHeader());
        // 阶段统计与摘要由产物现算：漏下发会让详情页缺「结构可用」结论
        assertNotNull(detail.getStageStats());
        assertEquals(3, detail.getStageStats().getElementCount());
        assertNotNull(detail.getStageSummary());
    }

    /**
     * 详情：无任务时各集合为空。
     *
     * <p>守护 {@code structureDetail}：无任务仍读产物即失败。
     */
    @Test
    void detailWithoutTaskShouldReturnEmptyCollections() {
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        when(fixture.pipelineTaskDbService.getByFileResultIdAndStage(10L, PipelineStage.STRUCTURE.name()))
                .thenReturn(null);

        StructureDetailVO detail = service.structureDetail(10L, null);

        assertNotNull(detail.getWarnings());
        assertTrue(detail.getWarnings().isEmpty());
        assertNotNull(detail.getConflicts());
        assertNotNull(detail.getOutline());
    }

    /**
     * 详情：指定任务环节不符按 40001 拒绝。
     *
     * <p>守护 {@code structureDetail}：错环节任务被展示即失败。
     */
    @Test
    void detailWithWrongStageTaskShouldReject() {
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        WbPipelineTask parseTask = new WbPipelineTask();
        parseTask.setId(20L);
        parseTask.setFileResultId(10L);
        parseTask.setStage(PipelineStage.PARSE.name());
        when(fixture.pipelineTaskDbService.getById(20L)).thenReturn(parseTask);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.structureDetail(10L, 20L));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
    }

    /**
     * 详情：产物对象位置不全按 40455 拒绝。
     *
     * <p>守护 {@code structureDetail}：半截位置去读对象即失败。
     */
    @Test
    void detailWithIncompleteProductLocationShouldReject40455() {
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        WbPipelineTask task = new WbPipelineTask();
        task.setId(51L);
        task.setStage(PipelineStage.STRUCTURE.name());
        task.setStatus(PipelineTaskStatus.SUCCESS.name());
        task.setProductId(50L);
        when(fixture.pipelineTaskDbService.getByFileResultIdAndStage(10L, PipelineStage.STRUCTURE.name()))
                .thenReturn(task);
        // 产物行缺 storageType / bucket：位置不完整，读取路径按「存储后端未配置」拒绝
        WbPipelineProduct product = new WbPipelineProduct();
        product.setArtifactId("def".repeat(16));
        when(fixture.pipelineProductDbService.getById(50L)).thenReturn(product);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.structureDetail(10L, null));

        assertEquals(ErrorCode.STORAGE_BACKEND_UNCONFIGURED, e.getErrorCode());
        verify(fixture.fileStorage, never()).getObject(any(ObjectRef.class));
    }

    /**
     * 详情：产物不可读时大纲留空、不阻断。
     *
     * <p>守护 {@code structureDetail}：读失败抛给用户即失败。
     */
    @Test
    void detailWithUnreadableProductShouldKeepOutlineEmpty() {
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        WbPipelineTask task = new WbPipelineTask();
        task.setId(51L);
        task.setStage(PipelineStage.STRUCTURE.name());
        task.setStatus(PipelineTaskStatus.SUCCESS.name());
        task.setProductId(50L);
        when(fixture.pipelineTaskDbService.getByFileResultIdAndStage(10L, PipelineStage.STRUCTURE.name()))
                .thenReturn(task);
        WbPipelineProduct product = new WbPipelineProduct();
        product.setArtifactId("def".repeat(16));
        product.setStorageType(StorageType.MINIO.getCode());
        product.setStorageSourceId(StructureControlTestFixture.SOURCE_ID);
        product.setBucket("artifacts");
        when(fixture.pipelineProductDbService.getById(50L)).thenReturn(product);
        // 非存储类读取失败（对象流异常）：记日志留空，不阻断详情
        when(fixture.fileStorage.getObject(ObjectRef.of(StructureControlTestFixture.SOURCE_ID,
                StorageType.MINIO.getCode(), "artifacts", "def".repeat(16))))
                .thenThrow(new IllegalStateException("对象流不可用"));

        StructureDetailVO detail = service.structureDetail(10L, null);

        assertEquals(51L, detail.getTaskId());
        assertNotNull(detail.getOutline());
        assertTrue(detail.getOutline().isEmpty());
        assertNotNull(detail.getWarnings());
        assertTrue(detail.getWarnings().isEmpty());
    }
}
