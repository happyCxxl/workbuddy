package com.workbuddy.model.route;

/**
 * 时间源：路由状态的占用窗口与延迟窗口都经本接口取当前时刻。
 * 默认实现取系统时钟，用例可注入可推进的实现。
 *
 * @author cxxl
 */
@FunctionalInterface
public interface TimeSource {

    /** 默认时间源：系统时钟 */
    TimeSource DEFAULT = System::currentTimeMillis;

    /**
     * 当前时刻。
     *
     * @return 毫秒时间戳
     */
    long now();
}
