package com.workbuddy.biz.domain;

import com.workbuddy.common.domain.entity.WbKnowledgeBase;
import com.workbuddy.common.domain.rules.KnowledgeBaseRules;
import com.workbuddy.common.enums.knowledge.KnowledgeBaseStatus;
import com.workbuddy.common.enums.user.UserRole;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.security.KnowledgeUser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 状态规则与可见范围规则纯单测（AIR/BCDE：合法路径 + 错误路径 + 边界 null）。
 */
class KnowledgeBaseRulesTest {

    private static final long ME = 1001L;

    private static final long OTHER = 2002L;

    private WbKnowledgeBase kb(Integer status) {
        WbKnowledgeBase k = new WbKnowledgeBase();
        k.setStatus(status);
        return k;
    }

    private KnowledgeUser user(long id, UserRole role) {
        KnowledgeUser u = new KnowledgeUser();
        u.setId(id);
        u.setRole(role);
        return u;
    }

    /** 归 ME 所有的知识库 */
    private WbKnowledgeBase ownedKb(Long ownerId) {
        WbKnowledgeBase k = kb(KnowledgeBaseStatus.ACTIVE.getCode());
        k.setUserId(ownerId);
        return k;
    }

    /**
     * 守护 {@code disableWhenActiveShouldPass}：该场景行为改动即失败。
     */
    @Test
    void disableWhenActiveShouldPass() {
        assertDoesNotThrow(() -> KnowledgeBaseRules.checkCanDisable(kb(KnowledgeBaseStatus.ACTIVE.getCode())));
    }

    /**
     * 守护 {@code disableWhenDisabledShouldThrow}：该场景行为改动即失败。
     */
    @Test
    void disableWhenDisabledShouldThrow() {
        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> KnowledgeBaseRules.checkCanDisable(kb(KnowledgeBaseStatus.DISABLED.getCode())));
        assertEquals(ErrorCode.KB_STATUS_ILLEGAL, e.getErrorCode());
    }

    /**
     * 守护 {@code disableWhenStatusNullShouldThrow}：该场景行为改动即失败。
     */
    @Test
    void disableWhenStatusNullShouldThrow() {
        assertThrows(KnowledgeException.class, () -> KnowledgeBaseRules.checkCanDisable(kb(null)));
    }

    /**
     * 守护 {@code enableWhenDisabledShouldPass}：该场景行为改动即失败。
     */
    @Test
    void enableWhenDisabledShouldPass() {
        assertDoesNotThrow(() -> KnowledgeBaseRules.checkCanEnable(kb(KnowledgeBaseStatus.DISABLED.getCode())));
    }

    /**
     * 守护 {@code enableWhenActiveShouldThrow}：该场景行为改动即失败。
     */
    @Test
    void enableWhenActiveShouldThrow() {
        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> KnowledgeBaseRules.checkCanEnable(kb(KnowledgeBaseStatus.ACTIVE.getCode())));
        assertEquals(ErrorCode.KB_STATUS_ILLEGAL, e.getErrorCode());
    }

    /**
     * 守护 {@code enableWhenStatusNullShouldThrow}：该场景行为改动即失败。
     */
    @Test
    void enableWhenStatusNullShouldThrow() {
        assertThrows(KnowledgeException.class, () -> KnowledgeBaseRules.checkCanEnable(kb(null)));
    }

    /**
     * 守护 {@code submitWhenActiveShouldPass}：该场景行为改动即失败。
     */
    @Test
    void submitWhenActiveShouldPass() {
        assertDoesNotThrow(() -> KnowledgeBaseRules.checkCanSubmit(kb(KnowledgeBaseStatus.ACTIVE.getCode())));
    }

    /**
     * 守护 {@code submitWhenDisabledShouldThrow}：该场景行为改动即失败。
     */
    @Test
    void submitWhenDisabledShouldThrow() {
        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> KnowledgeBaseRules.checkCanSubmit(kb(KnowledgeBaseStatus.DISABLED.getCode())));
        assertEquals(ErrorCode.KB_NOT_ACTIVE, e.getErrorCode());
    }

    /**
     * 守护 {@code submitWhenStatusNullShouldThrow}：该场景行为改动即失败。
     */
    @Test
    void submitWhenStatusNullShouldThrow() {
        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> KnowledgeBaseRules.checkCanSubmit(kb(null)));
        assertEquals(ErrorCode.KB_NOT_ACTIVE, e.getErrorCode());
    }

    // ---------------- 可见范围（归属）----------------

    /**
     * 守护 {@code visibleOwnerIdForNormalUserShouldBeSelf}：该场景行为改动即失败。
     */
    @Test
    void visibleOwnerIdForNormalUserShouldBeSelf() {
        assertEquals(ME, KnowledgeBaseRules.visibleOwnerId(user(ME, UserRole.USER)));
    }

    /**
     * 守护 {@code visibleOwnerIdForAdminShouldBeNullMeaningNoFilter}：该场景行为改动即失败。
     */
    @Test
    void visibleOwnerIdForAdminShouldBeNullMeaningNoFilter() {
        // null = 不加归属条件（管理员视角）；与"用户为 null"是两件事
        assertNull(KnowledgeBaseRules.visibleOwnerId(user(ME, UserRole.ADMIN)));
    }

    /**
     * 守护 {@code visibleOwnerIdWithoutLoginShouldThrow}：该场景行为改动即失败。
     */
    @Test
    void visibleOwnerIdWithoutLoginShouldThrow() {
        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> KnowledgeBaseRules.visibleOwnerId(null));
        assertEquals(ErrorCode.UNAUTHORIZED, e.getErrorCode());
    }

    /**
     * 守护 {@code visibleOwnerIdWithNullIdShouldThrow}：该场景行为改动即失败。
     */
    @Test
    void visibleOwnerIdWithNullIdShouldThrow() {
        KnowledgeUser user = user(ME, UserRole.USER);
        user.setId(null);

        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> KnowledgeBaseRules.visibleOwnerId(user));

        assertEquals(ErrorCode.UNAUTHORIZED, e.getErrorCode());
    }

    /**
     * 守护 {@code accessibleWhenOwnKbShouldPass}：该场景行为改动即失败。
     */
    @Test
    void accessibleWhenOwnKbShouldPass() {
        assertDoesNotThrow(() ->
                KnowledgeBaseRules.checkAccessible(ownedKb(ME), user(ME, UserRole.USER)));
    }

    /**
     * 守护 {@code accessibleWhenOthersKbShouldThrowNotFound}：该场景行为改动即失败。
     */
    @Test
    void accessibleWhenOthersKbShouldThrowNotFound() {
        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> KnowledgeBaseRules.checkAccessible(ownedKb(OTHER), user(ME, UserRole.USER)));
        // 抛"不存在"而不是"无权限"：后者等于确认该库存在，把别人的库 ID 变成可探测信息
        assertEquals(ErrorCode.KB_NOT_FOUND, e.getErrorCode());
    }

    /**
     * 守护 {@code accessibleWhenOwnerIsNullShouldThrowNotFoundForNormalUser}：该场景行为改动即失败。
     */
    @Test
    void accessibleWhenOwnerIsNullShouldThrowNotFoundForNormalUser() {
        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> KnowledgeBaseRules.checkAccessible(ownedKb(null), user(ME, UserRole.USER)));
        assertEquals(ErrorCode.KB_NOT_FOUND, e.getErrorCode());
    }

    /**
     * 守护 {@code accessibleForAdminShouldPassOnAnyKb}：该场景行为改动即失败。
     */
    @Test
    void accessibleForAdminShouldPassOnAnyKb() {
        assertDoesNotThrow(() ->
                KnowledgeBaseRules.checkAccessible(ownedKb(OTHER), user(ME, UserRole.ADMIN)));
        assertDoesNotThrow(() ->
                KnowledgeBaseRules.checkAccessible(ownedKb(null), user(ME, UserRole.ADMIN)));
    }

    /**
     * 守护 {@code accessibleWithoutLoginShouldThrowUnauthorized}：该场景行为改动即失败。
     */
    @Test
    void accessibleWithoutLoginShouldThrowUnauthorized() {
        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> KnowledgeBaseRules.checkAccessible(ownedKb(ME), null));
        assertEquals(ErrorCode.UNAUTHORIZED, e.getErrorCode());
    }

    /**
     * 守护 {@code accessibleWithNullKbShouldThrowNotFound}：该场景行为改动即失败。
     */
    @Test
    void accessibleWithNullKbShouldThrowNotFound() {
        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> KnowledgeBaseRules.checkAccessible(null, user(ME, UserRole.USER)));
        assertEquals(ErrorCode.KB_NOT_FOUND, e.getErrorCode());
    }
}
