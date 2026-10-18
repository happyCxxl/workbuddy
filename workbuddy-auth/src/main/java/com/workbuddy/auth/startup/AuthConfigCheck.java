package com.workbuddy.auth.startup;

import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.startup.StartupCheck;
import com.workbuddy.common.startup.StartupCheckChain;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.auth.config.JwtProperties;
import com.workbuddy.auth.config.LoginGuardProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 认证配置启动期校验：令牌签名密钥与两档有效期、登录失败防护的三个阈值。
 *
 * <p>密钥为空、有效期非正、防护阈值为非正都会让认证行为悄悄失去意义（空密钥签出的令牌没有防护力、
 * TTL 为 0 时令牌签发即过期、阈值为 0 时防护不生效），一律在启动期拒绝。
 * 密钥短于 {@value #MIN_SECRET_LENGTH} 字符只告警：本地开发用短密钥是常见做法，不阻断启动。
 *
 * <p>防护阈值只在 {@code knowledge.auth.guard.enabled=true} 时校验：关掉防护时这些值不参与任何判断。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthConfigCheck implements StartupCheck {

    /** 密钥长度告警门槛（HS256 的推荐密钥长度下限） */
    private static final int MIN_SECRET_LENGTH = 32;

    private final JwtProperties jwtProperties;

    private final LoginGuardProperties loginGuardProperties;

    @Override
    public int order() {
        return ORDER_CREDENTIALS;
    }

    @Override
    public void check(StartupCheckChain next) {
        checkJwt();
        checkGuard();
        next.proceed();
    }

    /** 令牌密钥与有效期 */
    private void checkJwt() {
        String secret = jwtProperties.getSecret();
        ThrowUtil.throwIf(NullUtil.isNull(secret) || secret.isBlank(), ErrorCode.PARAM_INVALID,
                "令牌签名密钥未配置（配置键 knowledge.auth.jwt.secret）");
        if (secret.trim().length() < MIN_SECRET_LENGTH) {
            log.warn("令牌签名密钥长度 {} 小于建议的 {} 字符（配置键 knowledge.auth.jwt.secret），生产环境请换成长随机串",
                    secret.trim().length(), MIN_SECRET_LENGTH);
        }
        ThrowUtil.throwIf(jwtProperties.getTtlDays() <= 0, ErrorCode.PARAM_INVALID,
                "令牌有效期必须为正数天，当前值 " + jwtProperties.getTtlDays() + "（配置键 knowledge.auth.jwt.ttl-days）");
        ThrowUtil.throwIf(jwtProperties.getTtlDaysRemember() <= 0, ErrorCode.PARAM_INVALID,
                "记住我的令牌有效期必须为正数天，当前值 " + jwtProperties.getTtlDaysRemember()
                        + "（配置键 knowledge.auth.jwt.ttl-days-remember）");
    }

    /** 登录失败防护阈值（防护关闭时不校验） */
    private void checkGuard() {
        if (!loginGuardProperties.isEnabled()) {
            log.info("登录失败防护未启用，跳过阈值校验");
            return;
        }
        ThrowUtil.throwIf(loginGuardProperties.getFailThreshold() <= 0, ErrorCode.PARAM_INVALID,
                "账号维度失败阈值必须为正数，当前值 " + loginGuardProperties.getFailThreshold()
                        + "（配置键 knowledge.auth.guard.fail-threshold）");
        ThrowUtil.throwIf(loginGuardProperties.getIpFailThreshold() <= 0, ErrorCode.PARAM_INVALID,
                "IP 维度失败阈值必须为正数，当前值 " + loginGuardProperties.getIpFailThreshold()
                        + "（配置键 knowledge.auth.guard.ip-fail-threshold）");
        ThrowUtil.throwIf(loginGuardProperties.getLockMinutes() <= 0, ErrorCode.PARAM_INVALID,
                "锁定时长必须为正数分钟，当前值 " + loginGuardProperties.getLockMinutes()
                        + "（配置键 knowledge.auth.guard.lock-minutes）");
    }
}
