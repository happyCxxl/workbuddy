package com.workbuddy.biz.startup;

import com.workbuddy.biz.config.RetrievalCapabilityProperties;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.startup.StartupCheck;
import com.workbuddy.common.startup.StartupCheckChain;
import com.workbuddy.worker.retrieval.RetrievalCapability;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 检索能力名启动期校验：`retrieval.capabilities.enabled` 里写的每个名字都必须是 {@link RetrievalCapability} 枚举名。
 *
 * <p>名字写错即该能力不会解锁：用到它的规则在保存或执行时被拒（40451），错误只说「该检索能力尚未启用」，
 * 不指出配置里的拼写问题；没被任何规则用到的错名一直无人察觉。本项在启动期把非法名与合法值一起列出来。
 *
 * @author cxxl
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetrievalCapabilityCheck implements StartupCheck {

    private final RetrievalCapabilityProperties properties;

    @Override
    public int order() {
        return ORDER_PARAMETERS;
    }

    @Override
    public void check(StartupCheckChain next) {
        Set<String> legal = Arrays.stream(RetrievalCapability.values())
                .map(Enum::name)
                .collect(Collectors.toUnmodifiableSet());
        Set<String> configured = properties.enabledNames();
        Set<String> unknown = configured.stream()
                .filter(name -> !legal.contains(name))
                .collect(Collectors.toUnmodifiableSet());
        ThrowUtil.throwIf(!unknown.isEmpty(), ErrorCode.PARAM_INVALID,
                "检索能力名非法 " + unknown + "（配置键 retrieval.capabilities.enabled，合法值 " + legal + "）");
        log.info("检索能力名校验通过, enabled={}", configured);
        next.proceed();
    }
}
