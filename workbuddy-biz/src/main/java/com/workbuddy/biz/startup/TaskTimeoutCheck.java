package com.workbuddy.biz.startup;

import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.startup.StartupCheck;
import com.workbuddy.common.startup.StartupCheckChain;
import com.workbuddy.biz.config.TaskQueueProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 单任务超时值启动期校验：配置里每个环节的超时都必须为正。
 *
 * <p>环节名写错由绑定期拦下（{@link TaskQueueProperties#timeout} 的键是枚举），本项补的是值域：
 * 超时给 0 或负数会让该环节任务一启动就被判超时，或在孤儿恢复里把正常任务误判成失败。
 *
 * @author cxxl
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskTimeoutCheck implements StartupCheck {

    private final TaskQueueProperties properties;

    @Override
    public int order() {
        return ORDER_PARAMETERS;
    }

    @Override
    public void check(StartupCheckChain next) {
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
        return duration == null || (!duration.isZero() && !duration.isNegative());
    }
}
