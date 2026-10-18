package com.workbuddy.common.enums.knowledge;

import com.workbuddy.common.utils.NullUtil;
import lombok.Getter;

/**
 * 策略绑定开关（wb_knowledge_base.strategy_binding_enabled，TINYINT 码值）。
 *
 * <p>**三态语义**：1 开启 / 0 关闭 / **null 视为开启**（历史数据与新建库都可能不落这一列）。
 * 判断"是否开启"一律走 {@link #isOn(Integer)}，不要在各处自己写 `!Integer.valueOf(0).equals(...)`。
 */
@Getter
public enum StrategyBindingSwitch {

    /** 开启：触发默认走知识库绑定策略 */
    ON(1),

    /** 关闭：测评模式，触发必须显式选策略 */
    OFF(0);

    private final int code;

    StrategyBindingSwitch(int code) {
        this.code = code;
    }

    /**
     * 按码值转枚举；null 与未知码值都返回 null。
     *
     * <p>与 {@link KnowledgeBaseStatus#of(Integer)} 不同，这里**不抛异常**：
     * 开关是可选列，脏数据不该把读路径打挂。
     */
    public static StrategyBindingSwitch of(Integer code) {
        if (NullUtil.isNull(code)) {
            return null;
        }
        for (StrategyBindingSwitch item : values()) {
            if (item.code == code) {
                return item;
            }
        }
        return null;
    }

    /** 是否开启：null 视为开启（与实体注释口径一致） */
    public static boolean isOn(Integer code) {
        return of(code) != OFF;
    }

    /** 是否关闭：null 视为开启，只有显式 0 才返回 true */
    public static boolean isOff(Integer code) {
        return of(code) == OFF;
    }
}
