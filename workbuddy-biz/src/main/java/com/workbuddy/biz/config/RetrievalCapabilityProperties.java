package com.workbuddy.biz.config;

import cn.hutool.core.util.StrUtil;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 检索能力开关配置（retrieval.capabilities 前缀）：能力分层解锁的清单，默认空即全关。
 *
 * <p>解锁只改配置：结构与策略快照零迁移。名字是否合法由启动校验项 {@code RetrievalCapabilityCheck} 判定。
 */
@Data
@Component
@ConfigurationProperties(prefix = "retrieval.capabilities")
public class RetrievalCapabilityProperties {

    /** 已启用的检索能力名（RetrievalCapability 枚举名；yaml 列表与逗号串两种写法都认） */
    private List<String> enabled = List.of();

    /** 归一化后的能力名：去空白、转大写，供注册表与启动校验共用。 */
    public Set<String> enabledNames() {
        return enabled.stream()
                .filter(StrUtil::isNotBlank)
                .map(name -> name.trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }
}
