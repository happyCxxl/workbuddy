package com.workbuddy.biz.service.support.retrieval;

import com.workbuddy.biz.config.RetrievalCapabilityProperties;
import com.workbuddy.worker.retrieval.RetrievalCapability;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 检索能力开关（能力分层解锁）：已启用能力取自 {@link RetrievalCapabilityProperties}（默认空 = 全关）。
 * 注册校验（RetrievalRuleResolver）与执行引擎共用本注册表——解锁只改配置，结构与快照零迁移。
 */
@Slf4j
@Component
public class RetrievalCapabilityRegistry {

    private final Set<String> enabled;

    /**
     * 构造注册表：一次性读出启用的能力名。
     *
     * @param properties 检索能力配置
     */
    public RetrievalCapabilityRegistry(RetrievalCapabilityProperties properties) {
        this.enabled = properties.enabledNames();
        log.info("===> RetrievalCapabilityRegistry 检索能力开关: {}", enabled);
    }

    /**
     * 能力是否已启用。
     *
     * @param capability 检索能力
     * @return 已启用为 true
     */
    public boolean isEnabled(RetrievalCapability capability) {
        return enabled.contains(capability.name());
    }
}
