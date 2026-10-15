package com.workbuddy.biz.service.support;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.worker.retrieval.RetrievalCapability;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 检索能力开关（能力分层解锁）：
 * 已启用能力来自配置项 `retrieval.capabilities.enabled`（逗号分隔 RetrievalCapability 枚举名，默认空=全关）。
 * 注册校验（RetrievalRuleResolver）与执行引擎共用本注册表——解锁只改配置，结构与快照零迁移。
 *
 * @author cxxl
 */
@Slf4j
@Component
public class RetrievalCapabilityRegistry {

    private final Set<String> enabled;

    public RetrievalCapabilityRegistry(@Value("${retrieval.capabilities.enabled:}") String enabledCsv) {
        this.enabled = StrUtil.isBlank(enabledCsv) ? Set.of()
                : Arrays.stream(enabledCsv.split(","))
                        .map(String::trim)
                        .filter(StrUtil::isNotBlank)
                        .map(String::toUpperCase)
                        .collect(Collectors.toUnmodifiableSet());
        log.info("===> RetrievalCapabilityRegistry 检索能力开关: {}", enabled);
    }

    /** 能力是否已启用 */
    public boolean isEnabled(RetrievalCapability capability) {
        return enabled.contains(capability.name());
    }
}
