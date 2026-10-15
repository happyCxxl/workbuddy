package com.workbuddy.model.startup;

import com.workbuddy.common.startup.StartupCheck;
import com.workbuddy.common.startup.StartupCheckChain;
import com.workbuddy.model.config.ModelRouteProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 路由参数启动期校验：调用 {@link ModelRouteProperties#validate()}，任一项越线即终止启动。
 *
 * <p>整套参数带默认值，配置文件里不写 `knowledge.model.route` 也能启动；写入的项才参与校验，
 * 错误信息带配置键全名与当前值。
 *
 * @author cxxl
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelRouteValidator implements StartupCheck {

    private final ModelRouteProperties properties;

    @Override
    public int order() {
        return ORDER_PARAMETERS;
    }

    @Override
    public void check(StartupCheckChain next) {
        properties.validate();
        log.info("模型路由参数校验通过, betaCapacity=[{}], betaLatency=[{}], capacityTriggerRatio=[{}], "
                        + "errorTriggerRatio=[{}], errorWindowSeconds=[{}], cooldownSeconds=[{}], slowStartSeconds=[{}]",
                properties.getBetaCapacity(), properties.getBetaLatency(), properties.getCapacityTriggerRatio(),
                properties.getErrorTriggerRatio(), properties.getErrorWindowSeconds(), properties.getCooldownSeconds(),
                properties.getSlowStartSeconds());
        next.proceed();
    }
}
