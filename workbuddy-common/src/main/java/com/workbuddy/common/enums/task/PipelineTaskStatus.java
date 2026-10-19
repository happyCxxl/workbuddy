package com.workbuddy.common.enums.task;

import com.workbuddy.common.utils.NullUtil;

import java.util.EnumSet;
import java.util.Set;

/**
 * 处理链任务状态：
 * QUEUED → RUNNING → SUCCESS / PARTIAL_SUCCESS / FAILED（CREATED 为入队前瞬时态，不落库）。
 * 失败即该环节终局，不设取消态：链路只由输入与策略推导，状态不参与链路决策。
 *
 * <p>合法迁移的权威定义是 {@link #allowedTargets()}，上面的箭头图只是它的摘要。
 */
public enum PipelineTaskStatus {

    /** 已入队待执行（建档 INSERT 即此态） */
    QUEUED,

    /** 执行中（执行器条件更新领取后） */
    RUNNING,

    /** 成功 */
    SUCCESS,

    /** 部分成功（部分元素/页面成功，保留告警） */
    PARTIAL_SUCCESS,

    /** 失败（保留错误与已生成产物，失败不覆盖成功） */
    FAILED;

    /**
     * 本状态允许迁移到的目标态；终态为空集（终态吸收：不再接受任何迁移）。
     *
     * @return 允许的目标状态集合
     */
    public Set<PipelineTaskStatus> allowedTargets() {
        return switch (this) {
            case QUEUED -> EnumSet.of(RUNNING, FAILED);
            case RUNNING -> EnumSet.of(SUCCESS, PARTIAL_SUCCESS, FAILED);
            case SUCCESS, PARTIAL_SUCCESS, FAILED -> EnumSet.noneOf(PipelineTaskStatus.class);
        };
    }

    /**
     * 是否终态（不再接受任何迁移）。
     *
     * @return 终态为 true
     */
    public boolean isTerminal() {
        return allowedTargets().isEmpty();
    }

    /**
     * 校验一次迁移是否合法；非法即抛内部异常（属编程缺陷，不做静默容忍），合法时返回目标态。
     *
     * @param from 源状态
     * @param to   目标状态
     * @return 目标状态
     */
    public static PipelineTaskStatus requireTransition(PipelineTaskStatus from, PipelineTaskStatus to) {
        if (NullUtil.isNull(from) || NullUtil.isNull(to)) {
            throw new IllegalArgumentException("任务状态迁移的源/目标不能为空");
        }
        if (!from.allowedTargets().contains(to)) {
            throw new IllegalArgumentException("非法的任务状态迁移：" + from + " → " + to
                    + "（" + from + " 只允许 " + from.allowedTargets() + "）");
        }
        return to;
    }
}
