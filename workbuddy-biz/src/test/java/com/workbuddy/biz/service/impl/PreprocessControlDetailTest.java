package com.workbuddy.biz.service.impl;

import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.domain.entity.WbPipelineStepLog;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.dto.response.preprocess.PreprocessDetailVO;
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
 * 预处理详情用例：任务态、子步骤、策略与元素逐列映射；空任务、错环节与存储位置错误码。
 *
 * <p>触发用例在 {@link PreprocessControlTriggerTest}；装配与夹具数据见 {@link PreprocessControlTestFixture}。
 */
@ExtendWith(MockitoExtension.class)
class PreprocessControlDetailTest {

    private PreprocessControlTestFixture fixture;

    private PreprocessControlServiceImpl service;

    @BeforeEach
    void setUp() {
        fixture = new PreprocessControlTestFixture();
        service = fixture.service();
    }

    /**
     * 详情：任务态、子步骤、策略与元素逐列映射。
     *
     * <p>守护 {@code preprocessDetail}：漏搬任一列即失败。
     */
    @Test
    void detailShouldAssembleTaskStepsStrategyAndElements() {
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        WbPipelineTask task = new WbPipelineTask();
        task.setId(31L);
        task.setStage(PipelineStage.PREPROCESS.name());
        task.setStatus(PipelineTaskStatus.SUCCESS.name());
        task.setProductId(50L);
        when(fixture.pipelineTaskDbService.getByFileResultIdAndStage(10L, PipelineStage.PREPROCESS.name()))
                .thenReturn(task);
        WbPipelineStepLog step = new WbPipelineStepLog();
        step.setStepName("字段标准化");
        step.setStatus("SUCCESS");
        step.setMatchedCount(3);
        step.setChangedCount(2);
        when(fixture.stepLogDbService.listByTaskId(31L)).thenReturn(List.of(step));
        WbPipelineProduct product = new WbPipelineProduct();
        product.setArtifactId("abc".repeat(16));
        // 产物行记下对象位置：详情读取按数据源与对象键定位
        product.setStorageType(StorageType.MINIO.getCode());
        product.setStorageSourceId(PreprocessControlTestFixture.SOURCE_ID);
        product.setBucket("artifacts");
        when(fixture.pipelineProductDbService.getById(50L)).thenReturn(product);
        String json = "{\"strategyVersion\":\"preproc-default-v1\","
                + "\"options\":{\"pageHeaderFooter\":\"MARK\"},"
                + "\"elements\":["
                + "{\"elementId\":\"h-1\",\"type\":\"TITLE\",\"status\":\"NORMAL\","
                + "\"displayText\":\"第一章\",\"normalizedText\":\"第一章\"},"
                + "{\"elementId\":\"n-1\",\"type\":\"PARAGRAPH\",\"status\":\"NORMAL\",\"rawText\":\"金额 叁佰万 元整\","
                + "\"displayText\":\"金额 叁佰万 元整\","
                + "\"normalizedText\":\"金额 3000000 元整\","
                + "\"normalizedFields\":[{\"field\":\"AMOUNT\",\"value\":\"3000000.00\","
                + "\"unit\":\"元\",\"rule\":\"amount-cn-v1\"}],"
                + "\"preprocessTrace\":[{\"rule\":\"amount-cn-v1\",\"field\":\"AMOUNT\","
                + "\"action\":\"EXTRACT\",\"before\":\"叁佰万\",\"after\":\"3000000.00\","
                + "\"evidence\":\"中文金额命中\"}]},"
                + "{\"elementId\":\"hdr-1\",\"type\":\"HEADER\",\"status\":\"EXCLUDED_HEADER\","
                + "\"rawText\":\"页眉文本\",\"displayText\":\"页眉文本\","
                + "\"preprocessTrace\":[{\"rule\":\"header-footer-detect-v1\","
                + "\"action\":\"EXCLUDE\",\"before\":\"页眉文本\",\"evidence\":\"多页重复页眉\"}]}]}";
        when(fixture.fileStorage.getObject(ObjectRef.of(PreprocessControlTestFixture.SOURCE_ID,
                StorageType.MINIO.getCode(), "artifacts", "abc".repeat(16))))
                .thenReturn(json.getBytes(StandardCharsets.UTF_8));

        PreprocessDetailVO detail = service.preprocessDetail(10L, null);

        assertEquals(31L, detail.getTaskId());
        assertEquals(PipelineTaskStatus.SUCCESS.name(), detail.getStatus());
        assertEquals(1, detail.getSteps().size());
        assertEquals(3, detail.getSteps().getFirst().getMatchedCount());
        assertNotNull(detail.getSummary());
        assertEquals(3, detail.getSummary().getElementCount());
        assertEquals(1, detail.getSummary().getChangedCount());
        assertEquals(1, detail.getSummary().getExcludedCount());
        assertEquals(1, detail.getSummary().getFieldCount());
        assertEquals(3, detail.getElements().size());
        assertEquals(1, detail.getElements().get(1).getFields().size());
        assertEquals("AMOUNT", detail.getElements().get(1).getFields().getFirst().getField());
        // 原文透传 + 处理轨迹透传（非 KEEP 条目）
        assertEquals("金额 叁佰万 元整", detail.getElements().get(1).getRawText());
        assertEquals(1, detail.getElements().get(1).getTrace().size());
        assertEquals("EXTRACT", detail.getElements().get(1).getTrace().getFirst().getAction());
        assertEquals("amount-cn-v1", detail.getElements().get(1).getTrace().getFirst().getRule());
        assertEquals("EXCLUDE", detail.getElements().get(2).getTrace().getFirst().getAction());
        assertEquals("多页重复页眉", detail.getElements().get(2).getTrace().getFirst().getEvidence());
        // 阶段统计与摘要由视图现算：漏下发会让详情页缺「剔除/仅标记」结论
        assertNotNull(detail.getStageStats());
        assertEquals(3, detail.getStageStats().getElementCount());
        assertNotNull(detail.getStageSummary());
    }

    /**
     * 详情：无任务时元素为空。
     *
     * <p>守护 {@code preprocessDetail}：无任务仍读产物即失败。
     */
    @Test
    void detailWithoutTaskShouldReturnEmptyElements() {
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        when(fixture.pipelineTaskDbService.getByFileResultIdAndStage(10L, PipelineStage.PREPROCESS.name()))
                .thenReturn(null);

        PreprocessDetailVO detail = service.preprocessDetail(10L, null);

        assertNotNull(detail.getElements());
        assertTrue(detail.getElements().isEmpty());
    }

    /**
     * 详情：指定任务环节不符按 40001 拒绝。
     *
     * <p>守护 {@code preprocessDetail}：错环节任务被展示即失败。
     */
    @Test
    void detailWithWrongStageTaskShouldReject() {
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        WbPipelineTask structureTask = new WbPipelineTask();
        structureTask.setId(51L);
        structureTask.setFileResultId(10L);
        structureTask.setStage(PipelineStage.STRUCTURE.name());
        when(fixture.pipelineTaskDbService.getById(51L)).thenReturn(structureTask);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.preprocessDetail(10L, 51L));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
    }

    /**
     * 详情：产物对象位置不全按 40455 拒绝。
     *
     * <p>守护 {@code preprocessDetail}：半截位置去读对象即失败。
     */
    @Test
    void detailWithIncompleteProductLocationShouldReject40455() {
        when(fixture.fileResultDbService.getById(10L)).thenReturn(fixture.fileResult());
        WbPipelineTask task = new WbPipelineTask();
        task.setId(31L);
        task.setStage(PipelineStage.PREPROCESS.name());
        task.setStatus(PipelineTaskStatus.SUCCESS.name());
        task.setProductId(50L);
        when(fixture.pipelineTaskDbService.getByFileResultIdAndStage(10L, PipelineStage.PREPROCESS.name()))
                .thenReturn(task);
        // 产物行缺 storageType / bucket：位置不完整，读取路径按「存储后端未配置」拒绝
        WbPipelineProduct product = new WbPipelineProduct();
        product.setArtifactId("abc".repeat(16));
        when(fixture.pipelineProductDbService.getById(50L)).thenReturn(product);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.preprocessDetail(10L, null));

        assertEquals(ErrorCode.STORAGE_BACKEND_UNCONFIGURED, e.getErrorCode());
        verify(fixture.fileStorage, never()).getObject(any(ObjectRef.class));
    }
}
