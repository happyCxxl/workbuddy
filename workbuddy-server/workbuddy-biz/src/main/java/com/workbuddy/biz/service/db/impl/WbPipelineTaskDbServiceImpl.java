package com.workbuddy.biz.service.db.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.workbuddy.biz.mapper.WbPipelineTaskMapper;
import com.workbuddy.biz.service.db.WbPipelineTaskDbService;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.infra.persistence.InfraDbServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 处理链任务数据访问服务实现。
 */
@Slf4j
@Service
public class WbPipelineTaskDbServiceImpl extends InfraDbServiceImpl<WbPipelineTaskMapper, WbPipelineTask>
        implements WbPipelineTaskDbService {

    /**
     * 按执行键取任务：四段键完全相同才算同一次执行，取最近一条。
     *
     * @param fileResultId      文件结果 ID
     * @param stage             环节名
     * @param upstreamProductId 上游产物 ID
     * @param strategyVersionId 策略版本行 ID
     * @return 命中的任务；没有则为 null
     */
    @Override
    public WbPipelineTask getByExecKey(Long fileResultId, String stage, Long upstreamProductId,
                                       Long strategyVersionId) {
        LambdaQueryWrapper<WbPipelineTask> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbPipelineTask::getFileResultId, fileResultId)
                .eq(WbPipelineTask::getStage, stage)
                .eq(WbPipelineTask::getUpstreamProductId, upstreamProductId)
                .eq(WbPipelineTask::getStrategyVersionId, strategyVersionId)
                .orderByDesc(WbPipelineTask::getId)
                .last("LIMIT 1");
        return getOne(queryWrapper, false);
    }

    /**
     * 取某文件某环节最近一条任务。
     *
     * @param fileResultId 文件结果 ID
     * @param stage        环节名
     * @return 命中的任务；没有则为 null
     */
    @Override
    public WbPipelineTask getByFileResultIdAndStage(Long fileResultId, String stage) {
        LambdaQueryWrapper<WbPipelineTask> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbPipelineTask::getFileResultId, fileResultId)
                .eq(WbPipelineTask::getStage, stage)
                .orderByDesc(WbPipelineTask::getId)
                .last("LIMIT 1");
        return getOne(queryWrapper, false);
    }

    /**
     * 批量取同环节任务，按 ID 降序。
     *
     * @param fileResultIds 文件结果 ID 列表
     * @param stage         环节名
     * @return 命中的任务列表；入参为空时返回空列表
     */
    @Override
    public List<WbPipelineTask> listByFileResultIdsAndStage(List<Long> fileResultIds, String stage) {
        if (NullUtil.isNull(fileResultIds) || fileResultIds.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<WbPipelineTask> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.in(WbPipelineTask::getFileResultId, fileResultIds)
                .eq(WbPipelineTask::getStage, stage)
                .orderByDesc(WbPipelineTask::getId);
        return list(queryWrapper);
    }

    /**
     * 按状态集合取任务，只带 ID、文件结果 ID 与状态三个字段。
     *
     * @param statuses 状态集合
     * @return 命中的任务列表；入参为空时返回空列表
     */
    @Override
    public List<WbPipelineTask> listByStatuses(List<PipelineTaskStatus> statuses) {
        if (NullUtil.isNull(statuses) || statuses.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<WbPipelineTask> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.select(WbPipelineTask::getId, WbPipelineTask::getFileResultId, WbPipelineTask::getStatus)
                .in(WbPipelineTask::getStatus, statuses.stream().map(Enum::name).toList())
                .orderByAsc(WbPipelineTask::getId);
        return list(queryWrapper);
    }

    /**
     * 取创建时间早于阈值、仍是排队态的任务（补投候选）。
     *
     * @param threshold 创建时间阈值
     * @return 命中的任务列表
     */
    @Override
    public List<WbPipelineTask> listStaleQueued(LocalDateTime threshold) {
        LambdaQueryWrapper<WbPipelineTask> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbPipelineTask::getStatus, PipelineTaskStatus.QUEUED.name())
                .lt(WbPipelineTask::getCreateTime, threshold);
        return list(queryWrapper);
    }

    /**
     * 取开始时间早于阈值、仍在运行态的任务（超时看门狗候选）。
     *
     * @param threshold 开始时间阈值
     * @return 命中的任务列表
     */
    @Override
    public List<WbPipelineTask> listStaleRunning(LocalDateTime threshold) {
        LambdaQueryWrapper<WbPipelineTask> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbPipelineTask::getStatus, PipelineTaskStatus.RUNNING.name())
                .lt(WbPipelineTask::getStartedAt, threshold);
        return list(queryWrapper);
    }

    /**
     * 把运行中的任务回写为终态。
     *
     * @param id        任务 ID
     * @param status    目标状态
     * @param errorCode 错误码（成功时为空）
     * @param errorMsg  错误信息（成功时为空）
     * @return 影响行数；状态已被其它路径改过时为 0
     */
    @Override
    public int finish(Long id, PipelineTaskStatus status, String errorCode, String errorMsg) {
        return finishFrom(id, PipelineTaskStatus.RUNNING, status, errorCode, errorMsg);
    }

    /**
     * 把仍在排队的任务直接置为失败（投递失败、任务作废）。
     *
     * @param id        任务 ID
     * @param errorCode 错误码
     * @param errorMsg  错误信息
     * @return 影响行数；状态已被其它路径改过时为 0
     */
    @Override
    public int failQueued(Long id, String errorCode, String errorMsg) {
        return finishFrom(id, PipelineTaskStatus.QUEUED, PipelineTaskStatus.FAILED, errorCode, errorMsg);
    }

    /** 终态回写：条件更新（from → to）+ 结束时间 + 错误码/信息；迁移先过真值表校验，后到者影响 0 行 */
    private int finishFrom(Long id, PipelineTaskStatus from, PipelineTaskStatus to,
                           String errorCode, String errorMsg) {
        PipelineTaskStatus.requireTransition(from, to);
        LambdaUpdateWrapper<WbPipelineTask> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(WbPipelineTask::getId, id)
                .eq(WbPipelineTask::getStatus, from.name())
                .set(WbPipelineTask::getStatus, to.name())
                .set(WbPipelineTask::getErrorCode, errorCode)
                .set(WbPipelineTask::getErrorMsg, errorMsg)
                .set(WbPipelineTask::getFinishedAt, LocalDateTime.now());
        int rows = baseMapper.update(null, updateWrapper);
        if (rows == 0) {
            log.info("任务状态迁移未生效，状态已被其他路径先写, taskId={}, from={}, to={}", id, from, to);
        }
        return rows;
    }

    /**
     * 取指定环节的排队任务，按 ID 升序取前 limit 条。
     *
     * @param stages 环节集合
     * @param limit  条数上限
     * @return 命中的任务列表；环节为空时返回空列表
     */
    @Override
    public List<WbPipelineTask> listQueuedByStages(List<PipelineStage> stages, int limit) {
        if (NullUtil.isNull(stages) || stages.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<WbPipelineTask> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.in(WbPipelineTask::getStage, stages.stream().map(Enum::name).toList())
                .eq(WbPipelineTask::getStatus, PipelineTaskStatus.QUEUED.name())
                .orderByAsc(WbPipelineTask::getId)
                .last("LIMIT " + limit);
        return list(queryWrapper);
    }

    /**
     * 抢占任务：条件更新把排队态改成运行态并写开始时间。
     *
     * @param id 任务 ID
     * @return 影响行数；并发下被别人先抢到时为 0
     */
    @Override
    public int claim(Long id) {
        PipelineTaskStatus.requireTransition(PipelineTaskStatus.QUEUED, PipelineTaskStatus.RUNNING);
        LambdaUpdateWrapper<WbPipelineTask> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(WbPipelineTask::getId, id)
                .eq(WbPipelineTask::getStatus, PipelineTaskStatus.QUEUED.name())
                .set(WbPipelineTask::getStatus, PipelineTaskStatus.RUNNING.name())
                .set(WbPipelineTask::getStartedAt, LocalDateTime.now());
        return baseMapper.update(null, updateWrapper);
    }

    /**
     * 回写任务产出的产物 ID。
     *
     * @param taskId    任务 ID
     * @param productId 产物 ID
     */
    @Override
    public void updateProductId(Long taskId, Long productId) {
        LambdaUpdateWrapper<WbPipelineTask> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(WbPipelineTask::getId, taskId)
                .set(WbPipelineTask::getProductId, productId);
        baseMapper.update(null, updateWrapper);
    }

    /**
     * 取某文件的全部任务，按 ID 升序（血缘与详情的时间线口径）。
     *
     * @param fileResultId 文件结果 ID
     * @return 任务列表
     */
    @Override
    public List<WbPipelineTask> listByFileResultId(Long fileResultId) {
        LambdaQueryWrapper<WbPipelineTask> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbPipelineTask::getFileResultId, fileResultId)
                .orderByAsc(WbPipelineTask::getId);
        return list(queryWrapper);
    }
}
