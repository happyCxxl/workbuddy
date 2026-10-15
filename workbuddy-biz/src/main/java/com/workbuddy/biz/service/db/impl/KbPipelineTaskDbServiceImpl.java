package com.workbuddy.biz.service.db.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.workbuddy.biz.mapper.KbPipelineTaskMapper;
import com.workbuddy.biz.service.db.KbPipelineTaskDbService;
import com.workbuddy.common.domain.entity.KbPipelineTask;
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
public class KbPipelineTaskDbServiceImpl extends InfraDbServiceImpl<KbPipelineTaskMapper, KbPipelineTask>
        implements KbPipelineTaskDbService {

    @Override
    public KbPipelineTask getByExecKey(Long fileResultId, String stage, Long upstreamProductId,
                                       Long strategyVersionId) {
        LambdaQueryWrapper<KbPipelineTask> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KbPipelineTask::getFileResultId, fileResultId)
                .eq(KbPipelineTask::getStage, stage)
                .eq(KbPipelineTask::getUpstreamProductId, upstreamProductId)
                .eq(KbPipelineTask::getStrategyVersionId, strategyVersionId)
                .orderByDesc(KbPipelineTask::getId)
                .last("LIMIT 1");
        return getOne(queryWrapper, false);
    }

    @Override
    public KbPipelineTask getByFileResultIdAndStage(Long fileResultId, String stage) {
        LambdaQueryWrapper<KbPipelineTask> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KbPipelineTask::getFileResultId, fileResultId)
                .eq(KbPipelineTask::getStage, stage)
                .orderByDesc(KbPipelineTask::getId)
                .last("LIMIT 1");
        return getOne(queryWrapper, false);
    }

    @Override
    public List<KbPipelineTask> listByFileResultIdsAndStage(List<Long> fileResultIds, String stage) {
        if (NullUtil.isNull(fileResultIds) || fileResultIds.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<KbPipelineTask> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.in(KbPipelineTask::getFileResultId, fileResultIds)
                .eq(KbPipelineTask::getStage, stage)
                .orderByDesc(KbPipelineTask::getId);
        return list(queryWrapper);
    }

    @Override
    public List<KbPipelineTask> listByStatuses(List<PipelineTaskStatus> statuses) {
        if (NullUtil.isNull(statuses) || statuses.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<KbPipelineTask> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.select(KbPipelineTask::getId, KbPipelineTask::getFileResultId, KbPipelineTask::getStatus)
                .in(KbPipelineTask::getStatus, statuses.stream().map(Enum::name).toList())
                .orderByAsc(KbPipelineTask::getId);
        return list(queryWrapper);
    }

    @Override
    public List<KbPipelineTask> listStaleQueued(LocalDateTime threshold) {
        LambdaQueryWrapper<KbPipelineTask> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KbPipelineTask::getStatus, PipelineTaskStatus.QUEUED.name())
                .lt(KbPipelineTask::getCreateTime, threshold);
        return list(queryWrapper);
    }

    @Override
    public List<KbPipelineTask> listStaleRunning(LocalDateTime threshold) {
        LambdaQueryWrapper<KbPipelineTask> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KbPipelineTask::getStatus, PipelineTaskStatus.RUNNING.name())
                .lt(KbPipelineTask::getStartedAt, threshold);
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
        LambdaUpdateWrapper<KbPipelineTask> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(KbPipelineTask::getId, id)
                .eq(KbPipelineTask::getStatus, from.name())
                .set(KbPipelineTask::getStatus, to.name())
                .set(KbPipelineTask::getErrorCode, errorCode)
                .set(KbPipelineTask::getErrorMsg, errorMsg)
                .set(KbPipelineTask::getFinishedAt, LocalDateTime.now());
        int rows = baseMapper.update(null, updateWrapper);
        if (rows == 0) {
            log.info("任务状态迁移未生效，状态已被其他路径先写, taskId={}, from={}, to={}", id, from, to);
        }
        return rows;
    }

    @Override
    public List<KbPipelineTask> listQueuedByStages(List<PipelineStage> stages, int limit) {
        if (NullUtil.isNull(stages) || stages.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<KbPipelineTask> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.in(KbPipelineTask::getStage, stages.stream().map(Enum::name).toList())
                .eq(KbPipelineTask::getStatus, PipelineTaskStatus.QUEUED.name())
                .orderByAsc(KbPipelineTask::getId)
                .last("LIMIT " + limit);
        return list(queryWrapper);
    }

    @Override
    public int claim(Long id) {
        PipelineTaskStatus.requireTransition(PipelineTaskStatus.QUEUED, PipelineTaskStatus.RUNNING);
        LambdaUpdateWrapper<KbPipelineTask> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(KbPipelineTask::getId, id)
                .eq(KbPipelineTask::getStatus, PipelineTaskStatus.QUEUED.name())
                .set(KbPipelineTask::getStatus, PipelineTaskStatus.RUNNING.name())
                .set(KbPipelineTask::getStartedAt, LocalDateTime.now());
        return baseMapper.update(null, updateWrapper);
    }

    @Override
    public void updateProductId(Long taskId, Long productId) {
        LambdaUpdateWrapper<KbPipelineTask> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(KbPipelineTask::getId, taskId)
                .set(KbPipelineTask::getProductId, productId);
        baseMapper.update(null, updateWrapper);
    }

    @Override
    public List<KbPipelineTask> listByFileResultId(Long fileResultId) {
        LambdaQueryWrapper<KbPipelineTask> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KbPipelineTask::getFileResultId, fileResultId)
                .orderByAsc(KbPipelineTask::getId);
        return list(queryWrapper);
    }
}
