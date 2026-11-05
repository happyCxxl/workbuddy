package com.workbuddy.infra.modelgateway;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 模型网关（灵犀）连接参数：Java 侧模型能力统一经本网关调用。
 * 键的来源：workbuddy-lingxi.yml（前缀 workbuddy.lingxi）；字段不带代码侧默认值，配置面是唯一基线。
 */
@Data
@Component
@ConfigurationProperties(prefix = "workbuddy.lingxi")
public class ModelGatewayProperties {

    /** 网关服务根地址 */
    private String baseUrl;

    /** 服务间令牌；为空时网关仅接受本机调用 */
    private String internalToken;

    /** 单次调用超时（毫秒，连接与读共用） */
    private int timeoutMs;
}
