package com.workbuddy.biz.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 检索运行记录配置（retrieval.run 前缀；同名 kebab-case 键在 config/knowledge/knowledge-retrieval.yml）。
 *
 * <p>字段不带代码侧默认值，配置面是唯一基线。测试台检索必记；生产检索是否落记录由
 * {@link #recordProduction} 决定（配置面默认关，逐查询记录量大）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "retrieval.run")
public class RetrievalRunProperties {

    /** 生产检索是否落运行记录 */
    private boolean recordProduction;
}
