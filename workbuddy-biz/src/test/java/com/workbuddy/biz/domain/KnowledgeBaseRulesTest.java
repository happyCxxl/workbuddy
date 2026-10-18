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

    @Test
    void disableWhenActiveShouldPass() {
        assertDoesNotThrow(() -> KnowledgeBaseRules.checkCanDisable(kb(KnowledgeBaseStatus.ACTIVE.getCode())));
    }

    @Test
    void disableWhenDisabledShouldThrow() {
        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> KnowledgeBaseRules.checkCanDisable(kb(KnowledgeBaseStatus.DISABLED.getCode())));
        assertEquals(ErrorCode.KB_STATUS_ILLEGAL, e.getErrorCode());
    }

    @Test
    void disableWhenStatusNullShouldThrow() {
        assertThrows(KnowledgeException.class, () -> KnowledgeBaseRules.checkCanDisable(kb(null)));
    }

    @Test
    void enableWhenDisabledShouldPass() {
        assertDoesNotThrow(() -> KnowledgeBaseRules.checkCanEnable(kb(KnowledgeBaseStatus.DISABLED.getCode())));
    }

    @Test
    void enableWhenActiveShouldThrow() {
        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> KnowledgeBaseRules.checkCanEnable(kb(KnowledgeBaseStatus.ACTIVE.getCode())));
        assertEquals(ErrorCode.KB_STATUS_ILLEGAL, e.getErrorCode());
    }

    @Test
    void enableWhenStatusNullShouldThrow() {
        assertThrows(KnowledgeException.class, () -> KnowledgeBaseRules.checkCanEnable(kb(null)));
    }

    @Test
    void submitWhenActiveShouldPass() {
        assertDoesNotThrow(() -> KnowledgeBaseRules.checkCanSubmit(kb(KnowledgeBaseStatus.ACTIVE.getCode())));
    }

    @Test
    void submitWhenDisabledShouldThrow() {
        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> KnowledgeBaseRules.checkCanSubmit(kb(KnowledgeBaseStatus.DISABLED.getCode())));
        assertEquals(ErrorCode.KB_NOT_ACTIVE, e.getErrorCode());
    }

    @Test
    void submitWhenStatusNullShouldThrow() {
        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> KnowledgeBaseRules.checkCanSubmit(kb(null)));
        assertEquals(ErrorCode.KB_NOT_ACTIVE, e.getErrorCode());
    }

    // ---------------- 可见范围（归属）----------------

    @Test
    void visibleOwnerIdForNormalUserShouldBeSelf() {
        assertEquals(ME, KnowledgeBaseRules.visibleOwnerId(user(ME, UserRole.USER)));
    }

    @Test
    void visibleOwnerIdForAdminShouldBeNullMeaningNoFilter() {
        // null = 不加归属条件（管理员视角）；与"用户为 null"是两件事
        assertNull(KnowledgeBaseRules.visibleOwnerId(user(ME, UserRole.ADMIN)));
    }

    @Test
    void visibleOwnerIdWithoutLoginShouldThrow() {
        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> KnowledgeBaseRules.visibleOwnerId(null));
        assertEquals(ErrorCode.UNAUTHORIZED, e.getErrorCode());
    }

    @Test
    void visibleOwnerIdWithNullIdShouldThrow() {
        KnowledgeUser user = user(ME, UserRole.USER);
        user.setId(null);

        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> KnowledgeBaseRules.visibleOwnerId(user));

        assertEquals(ErrorCode.UNAUTHORIZED, e.getErrorCode());
    }

    @Test
    void accessibleWhenOwnKbShouldPass() {
        assertDoesNotThrow(() ->
                KnowledgeBaseRules.checkAccessible(ownedKb(ME), user(ME, UserRole.USER)));
    }

    @Test
    void accessibleWhenOthersKbShouldThrowNotFound() {
        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> KnowledgeBaseRules.checkAccessible(ownedKb(OTHER), user(ME, UserRole.USER)));
        // 抛"不存在"而不是"无权限"：后者等于确认该库存在，把别人的库 ID 变成可探测信息
        assertEquals(ErrorCode.KB_NOT_FOUND, e.getErrorCode());
    }

    @Test
    void accessibleWhenOwnerIsNullShouldThrowNotFoundForNormalUser() {
        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> KnowledgeBaseRules.checkAccessible(ownedKb(null), user(ME, UserRole.USER)));
        assertEquals(ErrorCode.KB_NOT_FOUND, e.getErrorCode());
    }

    @Test
    void accessibleForAdminShouldPassOnAnyKb() {
        assertDoesNotThrow(() ->
                KnowledgeBaseRules.checkAccessible(ownedKb(OTHER), user(ME, UserRole.ADMIN)));
        assertDoesNotThrow(() ->
                KnowledgeBaseRules.checkAccessible(ownedKb(null), user(ME, UserRole.ADMIN)));
    }

    @Test
    void accessibleWithoutLoginShouldThrowUnauthorized() {
        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> KnowledgeBaseRules.checkAccessible(ownedKb(ME), null));
        assertEquals(ErrorCode.UNAUTHORIZED, e.getErrorCode());
    }

    @Test
    void accessibleWithNullKbShouldThrowNotFound() {
        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> KnowledgeBaseRules.checkAccessible(null, user(ME, UserRole.USER)));
        assertEquals(ErrorCode.KB_NOT_FOUND, e.getErrorCode());
    }
}
