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
 *
 * @author cxxl
 */
@Slf4j
@Service
public class WbPipelineTaskDbServiceImpl extends InfraDbServiceImpl<WbPipelineTaskMapper, WbPipelineTask>
        implements WbPipelineTaskDbService {

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

    @Override
    public WbPipelineTask getByFileResultIdAndStage(Long fileResultId, String stage) {
        LambdaQueryWrapper<WbPipelineTask> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbPipelineTask::getFileResultId, fileResultId)
                .eq(WbPipelineTask::getStage, stage)
                .orderByDesc(WbPipelineTask::getId)
                .last("LIMIT 1");
        return getOne(queryWrapper, false);
    }

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

    @Override
    public List<WbPipelineTask> listStaleQueued(LocalDateTime threshold) {
        LambdaQueryWrapper<WbPipelineTask> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbPipelineTask::getStatus, PipelineTaskStatus.QUEUED.name())
                .lt(WbPipelineTask::getCreateTime, threshold);
        return list(queryWrapper);
    }

    @Override
    public List<WbPipelineTask> listStaleRunning(LocalDateTime threshold) {
        LambdaQueryWrapper<WbPipelineTask> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbPipelineTask::getStatus, PipelineTaskStatus.RUNNING.name())
                .lt(WbPipelineTask::getStartedAt, threshold);
        return list(queryWrapper);
    }

    @Override
    public int finish(Long id, PipelineTaskStatus status, String errorCode, String errorMsg) {
        return finishFrom(id, PipelineTaskStatus.RUNNING, status, errorCode, errorMsg);
    }

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

    @Override
    public void updateProductId(Long taskId, Long productId) {
        LambdaUpdateWrapper<WbPipelineTask> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(WbPipelineTask::getId, taskId)
                .set(WbPipelineTask::getProductId, productId);
        baseMapper.update(null, updateWrapper);
    }

    @Override
    public List<WbPipelineTask> listByFileResultId(Long fileResultId) {
        LambdaQueryWrapper<WbPipelineTask> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbPipelineTask::getFileResultId, fileResultId)
                .orderByAsc(WbPipelineTask::getId);
        return list(queryWrapper);
    }
}
