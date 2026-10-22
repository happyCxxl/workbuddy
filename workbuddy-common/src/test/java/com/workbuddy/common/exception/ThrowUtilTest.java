package com.workbuddy.common.exception;

import com.workbuddy.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ThrowUtil 单测：条件成立按错误码抛出（默认 message / detail 覆盖）；条件不成立不抛；
 * 带补充动作的重载先执行动作再抛，且动作自身失败时不掩盖业务异常；
 * 内部异常走异常工厂重载（类型任意、条件不成立时不构造、消息可安全解引用被检查对象）。
 */
class ThrowUtilTest {

    /**
     * 守护 {@code throwIfShouldThrowSuppliedInternalException}：该场景行为改动即失败。
     */
    @Test
    void throwIfShouldThrowSuppliedInternalException() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ThrowUtil.throwIf(true, () -> new IllegalStateException("策略快照为空或已损坏")));

        assertEquals("策略快照为空或已损坏", e.getMessage());
    }

    /**
     * 守护 {@code throwIfShouldSupportAnyInternalExceptionType}：该场景行为改动即失败。
     */
    @Test
    void throwIfShouldSupportAnyInternalExceptionType() {
        assertThrows(IllegalArgumentException.class,
                () -> ThrowUtil.throwIf(true, () -> new IllegalArgumentException("入参非法")));
        assertThrows(UnsupportedOperationException.class,
                () -> ThrowUtil.throwIf(true, () -> new UnsupportedOperationException("不支持的类型")));
    }

    /**
     * 守护 {@code throwIfShouldNotEvaluateSupplierWhenConditionFalse}：该场景行为改动即失败。
     */
    @Test
    void throwIfShouldNotEvaluateSupplierWhenConditionFalse() {
        AtomicBoolean built = new AtomicBoolean(false);
        String nullable = null;

        assertDoesNotThrow(() -> ThrowUtil.throwIf(false, () -> {
            built.set(true);
            return new IllegalStateException("长度=" + nullable.length());
        }));

        assertFalse(built.get(), "条件不成立时异常工厂不应被调用");
    }

    /**
     * 守护 {@code throwIfShouldThrowWithDefaultMessage}：该场景行为改动即失败。
     */
    @Test
    void throwIfShouldThrowWithDefaultMessage() {
        WorkbuddyException e = assertThrows(WorkbuddyException.class,
                () -> ThrowUtil.throwIf(true, ErrorCode.LOGIN_FAILED));
        assertEquals(ErrorCode.LOGIN_FAILED.getCode(), e.getCode());
        assertEquals(ErrorCode.LOGIN_FAILED.getMessage(), e.getMessage());
    }

    /**
     * 守护 {@code throwIfShouldThrowWithDetailMessage}：该场景行为改动即失败。
     */
    @Test
    void throwIfShouldThrowWithDetailMessage() {
        WorkbuddyException e = assertThrows(WorkbuddyException.class,
                () -> ThrowUtil.throwIf(true, ErrorCode.USERNAME_EXISTS, "用户名 admin 已存在"));
        assertEquals(ErrorCode.USERNAME_EXISTS.getCode(), e.getCode());
        assertEquals("用户名 admin 已存在", e.getMessage());
    }

    /**
     * 守护 {@code throwIfShouldPassWhenConditionFalse}：该场景行为改动即失败。
     */
    @Test
    void throwIfShouldPassWhenConditionFalse() {
        assertDoesNotThrow(() -> ThrowUtil.throwIf(false, ErrorCode.LOGIN_FAILED));
        assertDoesNotThrow(() -> ThrowUtil.throwIf(false, ErrorCode.LOGIN_FAILED, "不会抛"));
    }

    /**
     * 守护 {@code throwIfShouldFallbackToDefaultMessageWhenDetailBlank}：该场景行为改动即失败。
     */
    @Test
    void throwIfShouldFallbackToDefaultMessageWhenDetailBlank() {
        WorkbuddyException nullDetail = assertThrows(WorkbuddyException.class,
                () -> ThrowUtil.throwIf(true, ErrorCode.USER_NOT_FOUND, (String) null));
        assertEquals(ErrorCode.USER_NOT_FOUND.getCode(), nullDetail.getCode());
        assertEquals(ErrorCode.USER_NOT_FOUND.getMessage(), nullDetail.getMessage());

        WorkbuddyException blankDetail = assertThrows(WorkbuddyException.class,
                () -> ThrowUtil.throwIf(true, ErrorCode.USER_NOT_FOUND, "   "));
        assertEquals(ErrorCode.USER_NOT_FOUND.getMessage(), blankDetail.getMessage());
    }

    /**
     * 守护 {@code throwIfShouldRunActionBeforeThrowing}：该场景行为改动即失败。
     */
    @Test
    void throwIfShouldRunActionBeforeThrowing() {
        List<String> trace = new ArrayList<>();

        WorkbuddyException e = assertThrows(WorkbuddyException.class,
                () -> ThrowUtil.throwIf(true, ErrorCode.LOGIN_FAILED, () -> trace.add("action")));

        assertEquals(ErrorCode.LOGIN_FAILED.getCode(), e.getCode());
        assertEquals(List.of("action"), trace);
    }

    /**
     * 守护 {@code throwIfShouldNotRunActionWhenConditionFalse}：该场景行为改动即失败。
     */
    @Test
    void throwIfShouldNotRunActionWhenConditionFalse() {
        AtomicBoolean ran = new AtomicBoolean(false);

        assertDoesNotThrow(() -> ThrowUtil.throwIf(false, ErrorCode.LOGIN_FAILED, () -> ran.set(true)));

        assertFalse(ran.get(), "条件不成立时补充动作不应执行");
    }

    /**
     * 守护 {@code throwIfShouldNotMaskBusinessErrorWhenActionFails}：该场景行为改动即失败。
     */
    @Test
    void throwIfShouldNotMaskBusinessErrorWhenActionFails() {
        WorkbuddyException e = assertThrows(WorkbuddyException.class,
                () -> ThrowUtil.throwIf(true, ErrorCode.USERNAME_EXISTS, "用户名已存在", () -> {
                    throw new IllegalStateException("补充动作自身失败");
                }));

        assertEquals(ErrorCode.USERNAME_EXISTS.getCode(), e.getCode());
        assertEquals("用户名已存在", e.getMessage());
        assertEquals(1, e.getSuppressed().length);
        assertEquals("补充动作自身失败", e.getSuppressed()[0].getMessage());
    }
}
