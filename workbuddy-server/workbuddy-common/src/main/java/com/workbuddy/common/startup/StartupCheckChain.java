package com.workbuddy.common.startup;

/**
 * 启动校验链：把控制权交给链上的下一项。
 *
 * <p>由启动校验的驱动方按 {@link StartupCheck#order()} 排好序后交付给第一项，之后每项自行推进。
 */
@FunctionalInterface
public interface StartupCheckChain {

    /** 交给链上的下一项；已是最后一项则返回 */
    void proceed();
}
