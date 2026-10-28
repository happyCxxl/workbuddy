package com.workbuddy.biz.startup;

import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.startup.StartupCheck;
import com.workbuddy.common.startup.StartupCheckChain;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.biz.config.TaskQueueProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 单任务超时值启动期校验：配置里每个环节的超时都必须为正。
 *
 * <p>环节名写错由绑定期拦下（{@link TaskQueueProperties} 的 timeout 键是枚举），本项补的是值域：
 * 超时给 0 或负数会让该环节任务一启动就被判超时，或在孤儿恢复里把正常任务误判成失败。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskTimeoutCheck implements StartupCheck {

    private final TaskQueueProperties properties;

    /**
     * 校验序号：参数类校验。
     *
     * @return 校验顺序
     */
    @Override
    public int order() {
        return ORDER_PARAMETERS;
    }

    /**
     * 校验各环节超时都是正数，通过后交给链上的下一项。
     *
     * @param next 启动校验链的下一项
     */
    @Override
    public void check(StartupCheckChain next) {
        if (NullUtil.isNull(properties.getTimeout())) {
            log.warn("未配置任何环节超时（`knowledge.task.timeout`），各环节统一按全局默认 5 分钟");
            next.proceed();
            return;
        }
        List<String> illegal = properties.getTimeout().entrySet().stream()
                .filter(entry -> !isPositive(entry.getValue()))
                .map(entry -> entry.getKey().name() + "=" + entry.getValue())
                .toList();
        ThrowUtil.throwIf(!illegal.isEmpty(), ErrorCode.PARAM_INVALID,
                "单任务超时必须为正数 " + illegal + "（配置键 knowledge.task.timeout.<环节>）");
        log.info("单任务超时校验通过, timeout={}", properties.getTimeout());
        next.proceed();
    }

    /** 时长为正：空值按未配置处理（由默认值兜底），不算非法 */
    private boolean isPositive(Duration duration) {
        return NullUtil.isNull(duration) || (!duration.isZero() && !duration.isNegative());
    }
}
