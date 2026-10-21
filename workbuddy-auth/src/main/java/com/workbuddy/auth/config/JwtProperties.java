package com.workbuddy.auth.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 令牌签发配置（knowledge.auth.jwt 前缀；同名 kebab-case 键在 config/workbuddy-auth.yml）。
 *
 * <p>字段不带代码侧默认值，配置面是唯一基线；取值域校验落在启动校验链（{@code AuthConfigCheck}）：
 * 密钥为空、有效期非正都在启动期拒绝。
 */
@Data
@Component
@ConfigurationProperties(prefix = "knowledge.auth.jwt")
public class JwtProperties {

    /** 签名密钥；仓库里的默认值是占位串，上线必须换掉 */
    private String secret;

    /** 不勾选「记住我」的令牌有效期（天） */
    private long ttlDays;

    /** 勾选「记住我」的令牌有效期（天） */
    private long ttlDaysRemember;
}
