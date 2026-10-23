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
 * <p>密钥为空、为旧版占位串、短于 {@value #MIN_SECRET_LENGTH} 字符、有效期非正、防护阈值为非正，
 * 都会让认证行为悄悄失去意义（空密钥签出的令牌没有防护力、TTL 为 0 时令牌签发即过期、
 * 阈值为 0 时防护不生效），一律在启动期拒绝。
 *
 * <p>防护阈值只在 {@code workbuddy.auth.guard.enabled=true} 时校验：关掉防护时这些值不参与任何判断。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthConfigCheck implements StartupCheck {

    /** 密钥长度下限（HS256 的推荐密钥长度下限） */
    private static final int MIN_SECRET_LENGTH = 32;

    /** 旧版仓库占位密钥：出现在任何配置源都拒绝启动，防止「默认值即可用」的假安全 */
    private static final String LEGACY_PLACEHOLDER_SECRET = "workbuddy-jwt-secret-change-me-in-production";

    private final JwtProperties jwtProperties;

    private final LoginGuardProperties loginGuardProperties;

    /**
     * 校验序号：凭据类校验排在能力类之前。
     *
     * @return 校验顺序
     */
    @Override
    public int order() {
        return ORDER_CREDENTIALS;
    }

    /**
     * 执行认证配置校验，通过后把控制权交给链上的下一项。
     *
     * @param next 启动校验链的下一项
     */
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
                "令牌签名密钥未配置（配置键 workbuddy.auth.jwt.secret，或环境变量 WORKBUDDY_JWT_SECRET）");
        ThrowUtil.throwIf(LEGACY_PLACEHOLDER_SECRET.equals(secret.trim()), ErrorCode.PARAM_INVALID,
                "令牌签名密钥仍是旧版占位串，请用环境变量 WORKBUDDY_JWT_SECRET 或本机覆盖文件提供（配置键 workbuddy.auth.jwt.secret）");
        ThrowUtil.throwIf(secret.trim().length() < MIN_SECRET_LENGTH, ErrorCode.PARAM_INVALID,
                "令牌签名密钥长度 " + secret.trim().length() + " 小于下限 " + MIN_SECRET_LENGTH
                        + " 字符（配置键 workbuddy.auth.jwt.secret）");
        ThrowUtil.throwIf(jwtProperties.getTtlDays() <= 0, ErrorCode.PARAM_INVALID,
                "令牌有效期必须为正数天，当前值 " + jwtProperties.getTtlDays() + "（配置键 workbuddy.auth.jwt.ttl-days）");
        ThrowUtil.throwIf(jwtProperties.getTtlDaysRemember() <= 0, ErrorCode.PARAM_INVALID,
                "记住我的令牌有效期必须为正数天，当前值 " + jwtProperties.getTtlDaysRemember()
                        + "（配置键 workbuddy.auth.jwt.ttl-days-remember）");
    }

    /** 登录失败防护阈值（防护关闭时不校验） */
    private void checkGuard() {
        if (!loginGuardProperties.isEnabled()) {
            log.info("登录失败防护未启用，跳过阈值校验");
            return;
        }
        ThrowUtil.throwIf(loginGuardProperties.getFailThreshold() <= 0, ErrorCode.PARAM_INVALID,
                "账号维度失败阈值必须为正数，当前值 " + loginGuardProperties.getFailThreshold()
                        + "（配置键 workbuddy.auth.guard.fail-threshold）");
        ThrowUtil.throwIf(loginGuardProperties.getIpFailThreshold() <= 0, ErrorCode.PARAM_INVALID,
                "IP 维度失败阈值必须为正数，当前值 " + loginGuardProperties.getIpFailThreshold()
                        + "（配置键 workbuddy.auth.guard.ip-fail-threshold）");
        ThrowUtil.throwIf(loginGuardProperties.getLockMinutes() <= 0, ErrorCode.PARAM_INVALID,
                "锁定时长必须为正数分钟，当前值 " + loginGuardProperties.getLockMinutes()
                        + "（配置键 workbuddy.auth.guard.lock-minutes）");
    }
}
