package com.workbuddy.common.exception;

import com.workbuddy.common.error.ErrorCode;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 校验守卫工具：条件成立时抛出异常。
 *
 * <p>两类口径分开：业务拒绝走 {@link #throwIf(boolean, ErrorCode)} 系列（KnowledgeException + 业务码，
 * 全局异常处理按业务异常记 warn 级日志）；内部缺陷（编程错误、开发期校验、"不该发生"的状态）走
 * {@link #throwIf(boolean, Supplier)}，保持 JDK 异常类型，全局异常处理按系统异常记 error 级日志。
 */
public final class ThrowUtil {

    private ThrowUtil() {
    }

    /**
     * 条件成立时按错误码抛出（使用默认消息）。
     *
     * @param condition 为 true 时抛出
     * @param errorCode 错误码
     */
    public static void throwIf(boolean condition, ErrorCode errorCode) {
        if (condition) {
            throw new WorkbuddyException(errorCode);
        }
    }

    /**
     * 条件成立时按错误码抛出，detail 覆盖默认消息。
     *
     * @param condition  为 true 时抛出
     * @param errorCode  错误码
     * @param errMessage 补充原因
     */
    public static void throwIf(boolean condition, ErrorCode errorCode, String errMessage) {
        if (condition) {
            throw new WorkbuddyException(errorCode, errMessage);
        }
    }

    /**
     * 条件成立时抛出指定的内部异常（异常在抛出时才构造）。
     *
     * <p>内部异常指编程缺陷、开发期校验与"不该发生"的状态：它们不是业务拒绝，保持 JDK 异常类型
     * （IllegalStateException / IllegalArgumentException / UnsupportedOperationException 等），由全局异常处理
     * 按系统异常记 error 级日志。业务拒绝一律走 {@link #throwIf(boolean, ErrorCode)} 系列。
     *
     * <p>这里接异常工厂而不是异常实例：条件不成立时完全不构造异常，消息里可以直接解引用被检查对象
     * （写成实例参数会在条件为 false 时也被求值，从而提前抛空指针）。新类型直接用工厂表达，本类无需再加重载。
     *
     * @param condition 为 true 时抛出
     * @param failure   异常工厂，不可为 null
     */
    public static void throwIf(boolean condition, Supplier<? extends RuntimeException> failure) {
        if (!condition) {
            return;
        }
        throw Objects.requireNonNull(failure, "failure 不可为 null").get();
    }

    /**
     * 条件成立时先执行补充动作，再按错误码抛出（使用默认消息）。
     *
     * <p>补充动作在抛出之前执行；它自身抛出的异常不替换业务异常，而是作为 suppressed 挂在业务异常上。
     *
     * @param condition   为 true 时抛出
     * @param errorCode   错误码
     * @param beforeThrow 抛出前的补充动作，不可为 null
     */
    public static void throwIf(boolean condition, ErrorCode errorCode, Runnable beforeThrow) {
        if (!condition) {
            return;
        }
        WorkbuddyException failure = new WorkbuddyException(errorCode);
        runBeforeThrow(beforeThrow, failure);
        throw failure;
    }

    /**
     * 条件成立时先执行补充动作，再按错误码抛出，detail 覆盖默认消息。
     *
     * @param condition   为 true 时抛出
     * @param errorCode   错误码
     * @param errMessage  补充原因
     * @param beforeThrow 抛出前的补充动作，不可为 null
     */
    public static void throwIf(boolean condition, ErrorCode errorCode, String errMessage,
            Runnable beforeThrow) {
        if (!condition) {
            return;
        }
        WorkbuddyException failure = new WorkbuddyException(errorCode, errMessage);
        runBeforeThrow(beforeThrow, failure);
        throw failure;
    }

    /** 补充动作失败不掩盖业务异常：降级为 suppressed */
    private static void runBeforeThrow(Runnable beforeThrow, WorkbuddyException failure) {
        Objects.requireNonNull(beforeThrow, "beforeThrow 不可为 null");
        try {
            beforeThrow.run();
        } catch (RuntimeException e) {
            failure.addSuppressed(e);
        }
    }
}
