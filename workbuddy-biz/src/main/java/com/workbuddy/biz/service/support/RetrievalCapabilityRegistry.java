package com.workbuddy.biz.service.support;

import com.workbuddy.biz.config.RetrievalCapabilityProperties;
import com.workbuddy.worker.retrieval.RetrievalCapability;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 检索能力开关（能力分层解锁）：已启用能力取自 {@link RetrievalCapabilityProperties}（默认空 = 全关）。
 * 注册校验（RetrievalRuleResolver）与执行引擎共用本注册表——解锁只改配置，结构与快照零迁移。
 *
 * @author cxxl
 */
@Slf4j
@Component
public class RetrievalCapabilityRegistry {

    private final Set<String> enabled;

    public RetrievalCapabilityRegistry(RetrievalCapabilityProperties properties) {
        this.enabled = properties.enabledNames();
        log.info("===> RetrievalCapabilityRegistry 检索能力开关: {}", enabled);
    }

    /** 能力是否已启用 */
    public boolean isEnabled(RetrievalCapability capability) {
        return enabled.contains(capability.name());
    }
}
