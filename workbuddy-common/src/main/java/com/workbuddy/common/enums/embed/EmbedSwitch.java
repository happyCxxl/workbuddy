package com.workbuddy.common.enums.embed;

/**
 * 向量化开关取值：与策略快照里的 `ON` / `OFF` 字符串同域。
 *
 * <p>配置侧（{@code knowledge.embed} 的三个开关）用本枚举而不是 String：取值写错会在绑定期直接失败，
 * 不会被当成 OFF 静默变形。
 */
public enum EmbedSwitch {

    /** 开 */
    ON,

    /** 关 */
    OFF
}
