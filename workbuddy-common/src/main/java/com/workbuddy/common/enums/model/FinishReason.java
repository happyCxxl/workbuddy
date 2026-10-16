package com.workbuddy.common.enums.model;

/**
 * 对话停止原因。
 *
 * @author cxxl
 */
public enum FinishReason {

    /** 自然说完 */
    STOP,

    /** 撞长度上限被截断 */
    LENGTH,

    /** 内容被安全过滤拦截 */
    CONTENT_FILTER,

    /** 其他 */
    OTHER
}
