package com.workbuddy.biz.service.support;

import com.workbuddy.biz.service.db.WbPipelineProductDbService;
import com.workbuddy.biz.service.db.WbPipelineStepLogDbService;
import com.workbuddy.biz.service.db.WbPipelineTaskDbService;
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.domain.entity.WbPipelineStepLog;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.dto.response.task.StageDetailVO;
import com.workbuddy.common.dto.response.task.StageStatsVO;
import com.workbuddy.biz.service.support.common.TaskDetailSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 环节详情助手单测。
 *
 * <p>守护三件事：详情骨架必须回填文件结果 ID（无任务时也要回填）、子步骤按任务映射、
 * 产物公共三字段随产物行下发；无任务/无产物时留空而不是伪造。
 */
@ExtendWith(MockitoExtension.class)
class TaskDetailSupportTest {

    @Mock
    private WbPipelineTaskDbService pipelineTaskDbService;

    @Mock
    private WbPipelineStepLogDbService stepLogDbService;

    @Mock
    private WbPipelineProductDbService pipelineProductDbService;

    private TaskDetailSupport support;

    /**
     * 每个用例重建助手。
     */
    @BeforeEach
    void setUp() {
        support = new TaskDetailSupport(pipelineTaskDbService, stepLogDbService, pipelineProductDbService);
    }

    /**
     * 有任务：回填文件结果、任务态字段与子步骤；返回传入的 VO 本身。
     *
     * <p>守护 {@code withTask}：文件结果 ID 漏填或子步骤丢失即失败。
     */
    @Test
    void withTaskShouldFillSkeletonAndSteps() {
        WbPipelineTask task = new WbPipelineTask();
        task.setId(41L);
        task.setStage("CHUNK");
        task.setStatus("SUCCESS");
        task.setErrorCode(null);
        task.setErrorMsg(null);
        WbPipelineStepLog stepLog = new WbPipelineStepLog();
        stepLog.setTaskId(41L);
        stepLog.setStepName("切分");
        stepLog.setStatus("SUCCESS");
        when(stepLogDbService.listByTaskId(41L)).thenReturn(List.of(stepLog));
        StageDetailVO<StageStatsVO> vo = new StageDetailVO<>();

        StageDetailVO<StageStatsVO> result = support.withTask(vo, 10L, task);

        assertSame(vo, result);
        assertEquals(10L, vo.getFileResultId());
        assertEquals(41L, vo.getTaskId());
        assertEquals("CHUNK", vo.getStage());
        assertEquals("SUCCESS", vo.getStatus());
        assertEquals(1, vo.getSteps().size());
        assertEquals("切分", vo.getSteps().getFirst().getStepName());
    }

    /**
     * 无任务：只回填文件结果 ID，其余留空且不查步骤日志。
     *
     * <p>守护 {@code withTask} 的无任务分支：空任务还查步骤会让详情多打一次库。
     */
    @Test
    void withTaskWithoutTaskShouldOnlyFillFileResultId() {
        StageDetailVO<StageStatsVO> vo = new StageDetailVO<>();

        support.withTask(vo, 10L, null);

        assertEquals(10L, vo.getFileResultId());
        assertNull(vo.getTaskId());
        assertNull(vo.getSteps());
        verify(stepLogDbService, never()).listByTaskId(org.mockito.ArgumentMatchers.any());
    }

    /**
     * 产物公共三字段回填：产物 ID、内容哈希、能力快照逐列下发。
     *
     * <p>守护 {@code withProductRef}：漏搬任一列都会让详情卡片缺产物引用。
     */
    @Test
    void withProductRefShouldFillThreeColumns() {
        WbPipelineProduct product = new WbPipelineProduct();
        product.setArtifactId("art-1");
        product.setContentHash("sha-1");
        product.setCapabilitySnapshot("{\"parserName\":\"pdfbox\"}");
        StageDetailVO<StageStatsVO> vo = new StageDetailVO<>();

        StageDetailVO<StageStatsVO> result = support.withProductRef(vo, product);

        assertSame(vo, result);
        assertEquals("art-1", vo.getArtifactId());
        assertEquals("sha-1", vo.getContentHash());
        assertEquals("{\"parserName\":\"pdfbox\"}", vo.getCapabilitySnapshot());
    }

    /**
     * 取任务产物：无任务或无产物 ID 时不查库；有 ID 时按行取。
     *
     * <p>守护 {@code productOfTask} 的空入参短路。
     */
    @Test
    void productOfTaskShouldShortCircuitOnMissingTaskOrProductId() {
        assertNull(support.productOfTask(null));
        WbPipelineTask noProduct = new WbPipelineTask();
        assertNull(support.productOfTask(noProduct));
        verify(pipelineProductDbService, never()).getById(org.mockito.ArgumentMatchers.any());

        WbPipelineTask withProduct = new WbPipelineTask();
        withProduct.setProductId(50L);
        WbPipelineProduct product = new WbPipelineProduct();
        product.setId(50L);
        when(pipelineProductDbService.getById(50L)).thenReturn(product);

        assertSame(product, support.productOfTask(withProduct));
    }
}
