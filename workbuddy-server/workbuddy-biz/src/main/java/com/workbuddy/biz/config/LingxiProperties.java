package com.workbuddy.biz.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 灵犀（Python）服务连接参数：模型能力统一由灵犀提供。
 */
@Data
@Component
@ConfigurationProperties(prefix = "workbuddy.lingxi")
public class LingxiProperties {

    /** 灵犀服务根地址 */
    private String baseUrl;

    /** 服务间令牌；为空时灵犀仅接受本机调用 */
    private String internalToken;

    /** 单次调用超时（毫秒） */
    private int timeoutMs;
}
