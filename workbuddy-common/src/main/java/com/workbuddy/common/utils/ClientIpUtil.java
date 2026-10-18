package com.workbuddy.common.utils;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 客户端 IP 工具：取当前请求的远端地址。
 *
 * <p>只认 {@code getRemoteAddr()}，不读 {@code X-Forwarded-For}：该请求头由客户端自行伪造，
 * 无条件信任等于把限流与锁定维度交给攻击者。将来前面加了反向代理，需在此处按可信代理白名单解析。
 */
public final class ClientIpUtil {

    /** 无请求上下文（后台线程/单元测试）时的占位值 */
    private static final String UNKNOWN = "unknown";

    private ClientIpUtil() {
    }

    /** 当前请求的客户端 IP；无请求上下文时返回 unknown */
    public static String getClientIp() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (NullUtil.isNull(attributes)) {
            return UNKNOWN;
        }
        return attributes.getRequest().getRemoteAddr();
    }
}
