package com.workbuddy.worker.pipeline;

import com.workbuddy.common.domain.task.StepLogInfo;
import com.workbuddy.common.enums.task.StepStatus;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 子步骤日志助手（各环节管线共用，静态工具）：
 * begin/finish 一对记真实耗时（统一口径）；elapsedMillis 供批量统计式步骤累计各阶段耗时；
 * build 供批量统计式步骤统一组装（状态 SUCCESS、平均长度=总长÷命中数）。
 * 收口解析/组装两份相同私有 newStep/finishStep，以及切片/向量化/预处理三家的手工构造。
 *
 * @author cxxl
 */
public final class StepLogHelper {

    private StepLogHelper() {
    }

    /** 开表：stepName + startedAt=now。 */
    public static StepLogInfo begin(String stepName) {
        StepLogInfo step = new StepLogInfo();
        step.setStepName(stepName);
        step.setStartedAt(LocalDateTime.now());
        return step;
    }

    /** 收表：finishedAt=now + duration=真实毫秒（startedAt→now）。 */
    public static void finish(StepLogInfo step) {
        step.setFinishedAt(LocalDateTime.now());
        step.setDuration((int) Duration.between(step.getStartedAt(), step.getFinishedAt()).toMillis());
    }

    /** 纳秒起点 → 已过毫秒（四舍五入；批量统计式步骤累计耗时用）。 */
    public static long elapsedMillis(long startedNanos) {
        return Math.round((System.nanoTime() - startedNanos) / 1_000_000.0);
    }

    /** 批量统计式子步骤组装：统一字段口径（状态 SUCCESS、平均长度=总长÷命中数）。 */
    public static StepLogInfo build(String stepName, String capability, int matched, int totalLen,
                                    int warnings, long durationMillis) {
        StepLogInfo step = begin(stepName);
        step.setStatus(StepStatus.SUCCESS.name());
        step.setCapabilityVersion(capability);
        step.setFinishedAt(LocalDateTime.now());
        step.setDuration((int) durationMillis);
        step.setMatchedCount(matched);
        step.setChangedCount(0);
        step.setAvgLen(matched > 0 ? totalLen / matched : 0);
        step.setWarningCount(warnings);
        return step;
    }
}
