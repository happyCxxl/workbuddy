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
 * 检索能力开关配置（retrieval.capabilities 前缀；同名 kebab-case 键在
 * config/knowledge/knowledge-retrieval.yml）。
 *
 * <p>字段不带代码侧默认值，配置面是唯一基线；清单为空即全关。
 * 解锁只改配置：结构与策略快照零迁移。名字是否合法由启动校验项 {@code RetrievalCapabilityCheck} 判定。
 */
@Data
@Component
@ConfigurationProperties(prefix = "retrieval.capabilities")
public class RetrievalCapabilityProperties {

    /** 已启用的检索能力名（RetrievalCapability 枚举名；yaml 列表与逗号串两种写法都认） */
    private List<String> enabled;

    /**
     * 归一化后的能力名：去空白、转大写，供注册表与启动校验共用。
     *
     * @return 能力名集合（不可变；清单未配置时为空集）
     */
    public Set<String> enabledNames() {
        List<String> configured = enabled;
        // 直接与 null 比较：SpotBugs 不识别 NullUtil（转调 Hutool）的判空，字段解引用会报 UWF
        if (configured == null) {
            return Set.of();
        }
        return configured.stream()
                .filter(StrUtil::isNotBlank)
                .map(name -> name.trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }
}
