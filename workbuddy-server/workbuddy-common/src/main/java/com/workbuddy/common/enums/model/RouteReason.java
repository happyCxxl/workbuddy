package com.workbuddy.common.enums.model;

import cn.hutool.core.util.StrUtil;

/**
 * 路由原因（调用明细表 route_reason 列取值，字典冻结）：本次调用落到该模型的选型依据。
 *
 * <p>取值与设计字典一致：常规、容量偏高、延迟偏高、健康不佳、恢复放行、由别的模型降级而来。
 * 无配额层、无灰度，故不含预算越线与影子流量两档。
 */
public enum RouteReason {

    /** 常规选中（无信号越线） */
    OK,

    /** 按容量占用切换 */
    CAPACITY_HIGH,

    /** 按延迟切换 */
    LATENCY_HIGH,

    /** 按健康状态切换 */
    UNHEALTHY,

    /** 恢复期放行（新上线或冷却恢复的爬升段） */
    SLOW_START,

    /** 由上一个模型降级而来 */
    FALLBACK_FROM;

    /**
     * 按取值解析路由原因（大小写不敏感）。
     *
     * @param code 取值（可空/未知）
     * @return 匹配的路由原因；空值与未知取值返回 null
     */
    public static RouteReason of(String code) {
        if (StrUtil.isBlank(code)) {
            return null;
        }
        for (RouteReason reason : values()) {
            if (reason.name().equalsIgnoreCase(code.trim())) {
                return reason;
            }
        }
        return null;
    }
}
