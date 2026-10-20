package com.workbuddy.biz.task.support;

import com.workbuddy.biz.service.db.WbPipelineTaskDbService;
import com.workbuddy.biz.service.support.access.ChainStorageSupport;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.dto.response.task.StageTriggerVO;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.enums.task.PipelineTaskErrorCode;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.utils.NullUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 环节任务触发共用助手（手动逐环节触发接口族共用）：
 * 存储一致性校验 + 执行键查重（已有任务一律拒绝 40431）+ 新建 QUEUED 任务入队，口径与各环节一致。
 *
 * <p>存储一致性校验委托 {@link ChainStorageSupport}（产物写入入口共用同一份判定）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskTriggerSupport {

    private final WbPipelineTaskDbService pipelineTaskDbService;
    private final ChainStorageSupport chainStorageSupport;
    private final TaskQueueSupport taskQueue;

    /**
     * 触发环节任务：先校验存储一致性（链的数据源与当前启用的数据源不一致 → 40453），
     * 再按「执行键」（文件结果 + 上游产物 + 策略版本）查重：已有任务一律拒绝 40431
     * （不因任务状态分流、也不返回既有结果），没有才新建 QUEUED 任务并入队。
     * 上游产物与策略版本用 0 表示"没有"那两档（解析没有上游也没有策略、组装没有策略）。
     *
     * @param fileResultId      文件结果 ID
     * @param stage             环节（PipelineStage）
     * @param upstreamProductId 上游产物 ID（可空；解析是链路起点，没有上游）
     * @param strategyVersionId 策略版本 ID（可空；解析/组装没有策略）
     * @param strategySnapshot  环节策略快照 JSON（可空；有策略的环节触发时固定，执行只用快照）
     * @param stageLabel        环节中文名（日志与拒绝文案用）
     * @return 触发响应（新建任务 ID）
     */
    public StageTriggerVO trigger(Long fileResultId, PipelineStage stage, Long upstreamProductId,
                                  Long strategyVersionId, String strategySnapshot, String stageLabel) {
        chainStorageSupport.requireStorageMatch(fileResultId);
        long upstream = NullUtil.isNull(upstreamProductId) ? 0L : upstreamProductId;
        long strategyVersion = NullUtil.isNull(strategyVersionId) ? 0L : strategyVersionId;
        WbPipelineTask existing = pipelineTaskDbService.getByExecKey(fileResultId, stage.name(),
                upstream, strategyVersion);
        ThrowUtil.throwIf(NullUtil.isNotNull(existing), ErrorCode.TASK_ALREADY_PENDING,
                NullUtil.isNull(existing) ? null
                        : stageLabel + "任务已创建（taskId=" + existing.getId() + "，状态 "
                        + existing.getStatus() + "），请勿重复触发");
        WbPipelineTask task = new WbPipelineTask();
        task.setFileResultId(fileResultId);
        task.setStage(stage.name());
        task.setStatus(PipelineTaskStatus.QUEUED.name());
        task.setUpstreamProductId(upstream);
        task.setStrategyVersionId(strategyVersion);
        task.setStrategySnapshot(strategySnapshot);
        pipelineTaskDbService.save(task);
        enqueueNewTask(task, stageLabel, fileResultId);
        log.info("===> TaskTriggerSupport trigger 触发{}任务, fileResultId={}, taskId={}, upstream={}, strategyVersion={}",
                stageLabel, fileResultId, task.getId(), upstream, strategyVersion);
        return new StageTriggerVO(task.getId());
    }

    /**
     * 新任务入队：无事务时写不进队列把任务落 FAILED（库里不留"永远排不上队"的 QUEUED），随后仍抛出；
     * 有事务时入队延迟到提交后，失败由启动补偿器补投（任务保持 QUEUED）。
     */
    private void enqueueNewTask(WbPipelineTask task, String stageLabel, Long fileResultId) {
        try {
            taskQueue.enqueue(task.getId());
        } catch (Exception e) {
            log.error("触发{}任务入队失败, fileResultId={}, taskId={}", stageLabel, fileResultId, task.getId(), e);
            pipelineTaskDbService.failQueued(task.getId(), PipelineTaskErrorCode.TASK_ENQUEUE_FAILED.name(),
                    "任务入队失败，请重试");
            throw e;
        }
    }
}
