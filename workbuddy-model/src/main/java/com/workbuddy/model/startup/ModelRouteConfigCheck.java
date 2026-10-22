package com.workbuddy.model.startup;

import com.workbuddy.common.startup.StartupCheck;
import com.workbuddy.common.startup.StartupCheckChain;
import com.workbuddy.model.config.ModelRouteProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 模型路由配置校验：冷却/窗口/尝试次数为正，失败率阈值落在 (0,1]。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelRouteConfigCheck implements StartupCheck {

    private final ModelRouteProperties properties;

    /**
     * 顺序档位：本地纯参数校验。
     *
     * @return {@link #ORDER_PARAMETERS}
     */
    @Override
    public int order() {
        return ORDER_PARAMETERS;
    }

    /**
     * 校验路由配置后推进。
     *
     * @param next 链上的下一项
     */
    @Override
    public void check(StartupCheckChain next) {
        if (properties.getCooldownSeconds() <= 0) {
            throw new IllegalStateException("模型路由冷却时长必须为正数（workbuddy.model.route.cooldown-seconds）");
        }
        if (properties.getSlowStartSeconds() < 0) {
            throw new IllegalStateException("模型路由 slow start 窗口不能为负数（workbuddy.model.route.slow-start-seconds）");
        }
        if (properties.getErrorTriggerRatio() <= 0 || properties.getErrorTriggerRatio() > 1) {
            throw new IllegalStateException("模型路由失败率阈值必须在 (0,1] 内（workbuddy.model.route.error-trigger-ratio）");
        }
        if (properties.getErrorWindowSeconds() <= 0) {
            throw new IllegalStateException("模型路由统计窗口必须为正数（workbuddy.model.route.error-window-seconds）");
        }
        if (properties.getMaxAttempts() <= 0) {
            throw new IllegalStateException("模型路由尝试上限必须为正数（workbuddy.model.route.max-attempts）");
        }
        log.info("===> ModelRouteConfigCheck 路由配置校验通过, cooldown={}s, slowStart={}s, maxAttempts={}",
                properties.getCooldownSeconds(), properties.getSlowStartSeconds(), properties.getMaxAttempts());
        next.proceed();
    }
}
