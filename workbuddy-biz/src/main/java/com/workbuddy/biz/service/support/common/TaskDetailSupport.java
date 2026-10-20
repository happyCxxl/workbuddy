package com.workbuddy.biz.service.support.common;

import com.workbuddy.biz.service.db.WbPipelineProductDbService;
import com.workbuddy.biz.service.db.WbPipelineStepLogDbService;
import com.workbuddy.biz.service.db.WbPipelineTaskDbService;
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.dto.response.task.StageDetailVO;
import com.workbuddy.common.dto.response.task.StepLogVO;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.utils.NullUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 环节详情共用助手：任务解析、详情骨架回填、该次运行的产物引用三件事各环节完全同构，
 * 集中在这里，各控制服务只留自己环节特有的产物装配。
 */
@Component
@RequiredArgsConstructor
public class TaskDetailSupport {

    private final WbPipelineTaskDbService pipelineTaskDbService;
    private final WbPipelineStepLogDbService stepLogDbService;
    private final WbPipelineProductDbService pipelineProductDbService;

    /**
     * 解析详情任务：taskId 指定则查该次运行并校验归属与环节（不匹配 40001）；缺省取该环节最新任务（可为空）。
     *
     * @param fileResultId 文件结果 ID
     * @param stage        环节
     * @param taskId       指定任务 ID（可空，空则取该环节最新任务）
     * @param stageLabel   环节中文名（错误提示用）
     * @return 任务行；缺省取最新且没有任务时为 null
     */
    public WbPipelineTask resolveTask(Long fileResultId, PipelineStage stage, Long taskId, String stageLabel) {
        if (NullUtil.isNull(taskId)) {
            return pipelineTaskDbService.getByFileResultIdAndStage(fileResultId, stage.name());
        }
        WbPipelineTask task = pipelineTaskDbService.getById(taskId);
        ThrowUtil.throwIf(NullUtil.isNull(task)
                || !fileResultId.equals(task.getFileResultId())
                || !stage.name().equals(task.getStage()), ErrorCode.PARAM_INVALID,
                "任务不存在或不属于该文件的" + stageLabel + "任务");
        return task;
    }

    /**
     * 详情骨架回填：文件结果 ID + 任务态字段 + 子步骤列表（无任务时只回填文件结果 ID，其余留空）。
     *
     * @param vo           环节详情 VO（各环节自己的子类）
     * @param fileResultId 文件结果 ID
     * @param task         该次运行任务（可为空）
     * @return 传入的 vo（状态与子步骤已回填）
     */
    public <T extends StageDetailVO<?>> T withTask(T vo, Long fileResultId, WbPipelineTask task) {
        vo.setFileResultId(fileResultId);
        if (NullUtil.isNotNull(task)) {
            vo.applyFrom(task);
            vo.setSteps(stepLogDbService.listByTaskId(task.getId()).stream().map(StepLogVO::of).toList());
        }
        return vo;
    }

    /**
     * 该次运行的产物：按 {@code task.productId} 取；无任务或无产物时返回 null（各环节据此留空）。
     *
     * @param task 任务行（可空）
     * @return 产物行；无任务、无产物或产物不存在时为 null
     */
    public WbPipelineProduct productOfTask(WbPipelineTask task) {
        return NullUtil.isNull(task) || NullUtil.isNull(task.getProductId()) ? null
                : pipelineProductDbService.getById(task.getProductId());
    }

    /**
     * 产物公共三字段回填：产物 ID、内容哈希、能力快照（各环节详情共有的产物引用口径）。
     *
     * @param vo      环节详情 VO
     * @param product 该次运行的产物（非空）
     * @return 传入的 vo（产物三字段已回填）
     */
    public <T extends StageDetailVO<?>> T withProductRef(T vo, WbPipelineProduct product) {
        vo.setArtifactId(product.getArtifactId());
        vo.setContentHash(product.getContentHash());
        vo.setCapabilitySnapshot(product.getCapabilitySnapshot());
        return vo;
    }
}
