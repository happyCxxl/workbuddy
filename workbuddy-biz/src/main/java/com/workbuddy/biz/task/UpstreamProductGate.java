package com.workbuddy.biz.task;

import com.workbuddy.biz.service.db.KbPipelineTaskDbService;
import com.workbuddy.common.domain.entity.KbPipelineProduct;
import com.workbuddy.common.domain.entity.KbPipelineTask;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.utils.NullUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 下游门禁：下游环节只消费「产出任务已成功或部分成功」的上游产物。
 *
 * <p>事实来源是**产出任务的状态**，不是产物行自身：任务判超时后旧执行仍可能把产物写进存储，
 * 终态条件回写又可能 0 行（已经被看门狗写成 FAILED）。这类产物在下游看来"存在且环节匹配"，
 * 但它的运行并没有成功，继续消费会把失败结果当成上游输入。
 *
 * <p>放行集是 {@code SUCCESS} 与 {@code PARTIAL_SUCCESS} 两种终态；其余状态（{@code FAILED}、
 * {@code QUEUED}、{@code RUNNING}）一律拒绝。
 *
 * @author cxxl
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UpstreamProductGate {

    private final KbPipelineTaskDbService pipelineTaskDbService;

    /**
     * 校验上游产物可被下游消费：按产物行上的产出任务查状态，不在放行集内即拒绝。
     *
     * @param product 上游产物行（读 taskId 定位产出任务）
     */
    public void requireConsumable(KbPipelineProduct product) {
        Long taskId = product.getTaskId();
        KbPipelineTask task = NullUtil.isNull(taskId) ? null : pipelineTaskDbService.getById(taskId);
        ThrowUtil.throwIf(NullUtil.isNull(task), ErrorCode.TASK_UPSTREAM_NOT_READY,
                "上游产物的产出任务不存在，请重新触发上游环节");
        String status = task.getStatus();
        boolean partial = PipelineTaskStatus.PARTIAL_SUCCESS.name().equals(status);
        boolean consumable = PipelineTaskStatus.SUCCESS.name().equals(status) || partial;
        ThrowUtil.throwIf(!consumable, ErrorCode.TASK_UPSTREAM_NOT_READY,
                "上游产物来自未成功的运行（状态 " + status + "），请重新触发上游环节");
        if (partial) {
            log.warn("===> UpstreamProductGate 上游产物为部分成功，放行下游, taskId={}, productId={}, stage={}",
                    taskId, product.getId(), product.getStage());
        }
    }
}
