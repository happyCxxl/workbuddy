package com.workbuddy.auth.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 令牌签发配置（workbuddy.auth.jwt 前缀；同名 kebab-case 键在 config/workbuddy-auth.yml）。
 *
 * <p>字段不带代码侧默认值，配置面是唯一基线；取值域校验落在启动校验链（{@code AuthConfigCheck}）：
 * 密钥为空、旧版占位串、短于 32 字符、有效期非正都在启动期拒绝。
 */
@Data
@Component
@ConfigurationProperties(prefix = "workbuddy.auth.jwt")
public class JwtProperties {

    /** 签名密钥；仓库默认留空（${WORKBUDDY_JWT_SECRET:}），本机真值走环境变量或 application-local.yml，为空启动即拒绝 */
    private String secret;

    /** 不勾选「记住我」的令牌有效期（天） */
    private long ttlDays;

    /** 勾选「记住我」的令牌有效期（天） */
    private long ttlDaysRemember;
}
