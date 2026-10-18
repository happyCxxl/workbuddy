package com.workbuddy.biz.task.runner.persistence;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.workbuddy.biz.service.db.WbPipelineStepLogDbService;
import com.workbuddy.common.domain.entity.WbPipelineStepLog;
import com.workbuddy.common.domain.task.StepLogInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 子步骤日志落库（各环节任务执行器共用）：StepLogInfo → wb_knowledge_pipeline_step_log 逐条保存。
 * 收口五处同构的 saveStepLogs 私有副本（解析/组装/预处理/切片/向量化）；
 * 默认值补齐（attempt=1、warning/matched/changed/avgLen=0）+ 错误文案截断（1000 字符）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StepLogPersistence {

    private final WbPipelineStepLogDbService stepLogDbService;

    /** 逐条保存子步骤日志（公共字段拷贝 + 默认值补齐 + 错误文案截断）。 */
    public void save(Long taskId, List<StepLogInfo> stepLogs) {
        for (StepLogInfo step : stepLogs) {
            WbPipelineStepLog stepLog = new WbPipelineStepLog();
            stepLog.copyFrom(step);
            stepLog.setTaskId(taskId);
            stepLog.setWarningCount(ObjectUtil.defaultIfNull(stepLog.getWarningCount(), 0));
            stepLog.setMatchedCount(ObjectUtil.defaultIfNull(stepLog.getMatchedCount(), 0));
            stepLog.setChangedCount(ObjectUtil.defaultIfNull(stepLog.getChangedCount(), 0));
            stepLog.setAvgLen(ObjectUtil.defaultIfNull(stepLog.getAvgLen(), 0));
            stepLog.setError(StrUtil.isBlank(stepLog.getError()) ? null : truncate(stepLog.getError()));
            stepLogDbService.save(stepLog);
        }
    }

    private String truncate(String message) {
        return StrUtil.maxLength(message, 1000);
    }
}
