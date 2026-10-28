package com.workbuddy.common.enums.user;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 用户角色枚举测试。
 */
class UserRoleTest {

    /**
     * 守护 {@code ofShouldMatchCodeIgnoringCaseAndSpaces}：该场景行为改动即失败。
     */
    @Test
    void ofShouldMatchCodeIgnoringCaseAndSpaces() {
        assertEquals(UserRole.ADMIN, UserRole.of("ADMIN"));
        assertEquals(UserRole.ADMIN, UserRole.of("admin"));
        assertEquals(UserRole.ADMIN, UserRole.of("  Admin  "));
        assertEquals(UserRole.USER, UserRole.of("user"));
    }

    /**
     * 守护 {@code ofShouldFallbackToUserWhenNull}：该场景行为改动即失败。
     */
    @Test
    void ofShouldFallbackToUserWhenNull() {
        assertEquals(UserRole.USER, UserRole.of(null));
    }

    /**
     * 守护 {@code ofShouldFallbackToUserWhenUnknownCode}：该场景行为改动即失败。
     */
    @Test
    void ofShouldFallbackToUserWhenUnknownCode() {
        // 库中出现未知角色值时不得构造出「无角色」用户，回落最小权限
        assertEquals(UserRole.USER, UserRole.of("SUPERMAN"));
        assertEquals(UserRole.USER, UserRole.of(""));
    }
}
