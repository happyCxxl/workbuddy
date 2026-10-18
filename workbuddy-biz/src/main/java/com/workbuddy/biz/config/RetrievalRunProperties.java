package com.workbuddy.biz.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 检索运行记录配置（retrieval.run 前缀）。
 *
 * <p>测试台检索必记；生产检索是否落记录由 {@link #recordProduction} 决定，默认关（逐查询记录量大）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "retrieval.run")
public class RetrievalRunProperties {

    /** 生产检索是否落运行记录 */
    private boolean recordProduction = false;
}
