package com.workbuddy.common.enums.model;

import cn.hutool.core.util.StrUtil;

/**
 * 模型调用状态（调用明细表 status 列取值，字典冻结）：一次调用的最终结论。
 *
 * <p>取值与设计字典一致：成功、通用失败、超时、被限流、熔断放行被拒。
 * 熔断那一档由冷却 / 熔断片产生，本片写出的取值只落在前四档。
 *
 * @author cxxl
 */
public enum CallStatus {

    /** 调用成功 */
    SUCCESS,

    /** 调用失败（未归入下列专项档的失败） */
    FAILED,

    /** 调用超时 */
    TIMEOUT,

    /** 被厂商限流 */
    RATE_LIMITED,

    /** 熔断打开期间被拒 */
    CIRCUIT_OPEN;

    /**
     * 按取值解析状态（大小写不敏感）。
     *
     * @param code 状态取值（可空/未知）
     * @return 匹配的状态；空值与未知取值返回 null
     */
    public static CallStatus of(String code) {
        if (StrUtil.isBlank(code)) {
            return null;
        }
        for (CallStatus status : values()) {
            if (status.name().equalsIgnoreCase(code.trim())) {
                return status;
            }
        }
        return null;
    }
}
