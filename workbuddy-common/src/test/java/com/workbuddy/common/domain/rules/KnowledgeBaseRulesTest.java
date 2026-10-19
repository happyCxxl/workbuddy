package com.workbuddy.common.domain.rules;

import com.workbuddy.common.domain.entity.WbKnowledgeBase;
import com.workbuddy.common.enums.knowledge.KnowledgeBaseStatus;
import com.workbuddy.common.enums.user.UserRole;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.security.KnowledgeUser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 知识库规则单测。
 *
 * <p>守护三块契约：状态两态迁移闸门（启用↔停用、提交须启用）、可见范围
 * （管理员不过滤、普通用户只看自己、无登录一律 40101 而不是退化成全量可见）、
 * 越权与不存在同响应（40401，不泄露他人库 ID 的存在性）。
 */
class KnowledgeBaseRulesTest {

    /**
     * 停用仅允许启用状态。
     *
     * <p>守护 {@code checkCanDisable}（错误码 40402）：停用态再停用即失败。
     */
    @Test
    void disableShouldRequireActiveStatus() {
        assertDoesNotThrow(() -> KnowledgeBaseRules.checkCanDisable(kb(KnowledgeBaseStatus.ACTIVE, null)));

        KnowledgeException disabled = assertThrows(KnowledgeException.class,
                () -> KnowledgeBaseRules.checkCanDisable(kb(KnowledgeBaseStatus.DISABLED, null)));
        assertEquals(ErrorCode.KB_STATUS_ILLEGAL.getCode(), disabled.getCode());

        KnowledgeException missing = assertThrows(KnowledgeException.class,
                () -> KnowledgeBaseRules.checkCanDisable(kb(null, null)));
        assertEquals(ErrorCode.KB_STATUS_ILLEGAL.getCode(), missing.getCode());
    }

    /**
     * 启用仅允许停用状态。
     *
     * <p>守护 {@code checkCanEnable}（错误码 40402）：启用态再启用即失败。
     */
    @Test
    void enableShouldRequireDisabledStatus() {
        assertDoesNotThrow(() -> KnowledgeBaseRules.checkCanEnable(kb(KnowledgeBaseStatus.DISABLED, null)));

        KnowledgeException active = assertThrows(KnowledgeException.class,
                () -> KnowledgeBaseRules.checkCanEnable(kb(KnowledgeBaseStatus.ACTIVE, null)));
        assertEquals(ErrorCode.KB_STATUS_ILLEGAL.getCode(), active.getCode());
    }

    /**
     * 提交文档仅允许启用状态。
     *
     * <p>守护 {@code checkCanSubmit}（错误码 40421）。
     */
    @Test
    void submitShouldRequireActiveStatus() {
        assertDoesNotThrow(() -> KnowledgeBaseRules.checkCanSubmit(kb(KnowledgeBaseStatus.ACTIVE, null)));

        KnowledgeException ex = assertThrows(KnowledgeException.class,
                () -> KnowledgeBaseRules.checkCanSubmit(kb(KnowledgeBaseStatus.DISABLED, null)));
        assertEquals(ErrorCode.KB_NOT_ACTIVE.getCode(), ex.getCode());
    }

    /**
     * 可见范围：管理员不过滤（null），普通用户只看自己。
     *
     * <p>守护 {@code visibleOwnerId}：把管理员的 null 误当未登录、或普通用户返回错 ID 即失败。
     */
    @Test
    void visibleOwnerShouldFilterOnlyForNormalUser() {
        assertNull(KnowledgeBaseRules.visibleOwnerId(user(1L, UserRole.ADMIN)));
        assertEquals(9L, KnowledgeBaseRules.visibleOwnerId(user(9L, UserRole.USER)));
    }

    /**
     * 无登录上下文（含用户 ID 缺失）一律拒绝，不退化成全量可见。
     *
     * <p>守护 {@code requireLogin}（错误码 40101）：缺失上下文返回 null（=不过滤）即失败。
     */
    @Test
    void visibilityShouldRejectMissingLoginContext() {
        assertEquals(ErrorCode.UNAUTHORIZED.getCode(),
                assertThrows(KnowledgeException.class, () -> KnowledgeBaseRules.visibleOwnerId(null)).getCode());
        assertEquals(ErrorCode.UNAUTHORIZED.getCode(),
                assertThrows(KnowledgeException.class,
                        () -> KnowledgeBaseRules.visibleOwnerId(user(null, UserRole.USER))).getCode());
    }

    /**
     * 归属校验：管理员放行全部，普通用户仅限自己的库。
     *
     * <p>守护 {@code checkAccessible}：普通用户读到他人库即失败。
     */
    @Test
    void accessibleShouldAllowAdminAndOwnerOnly() {
        WbKnowledgeBase own = kb(KnowledgeBaseStatus.ACTIVE, 9L);
        WbKnowledgeBase others = kb(KnowledgeBaseStatus.ACTIVE, 8L);

        assertDoesNotThrow(() -> KnowledgeBaseRules.checkAccessible(others, user(9L, UserRole.ADMIN)));
        assertDoesNotThrow(() -> KnowledgeBaseRules.checkAccessible(own, user(9L, UserRole.USER)));

        assertEquals(ErrorCode.KB_NOT_FOUND.getCode(),
                assertThrows(KnowledgeException.class,
                        () -> KnowledgeBaseRules.checkAccessible(others, user(9L, UserRole.USER))).getCode());
    }

    /**
     * 越权与不存在同响应；无归属的库对普通用户不可见。
     *
     * <p>守护 {@code checkAccessible}（错误码 40401）：库缺失、归属缺失、归属不符都返回
     * 同一错误码——把越权与不存在区分开即失败（他人库 ID 变成可探测信息）。
     */
    @Test
    void accessibleShouldNotDistinguishForbiddenFromMissing() {
        assertEquals(ErrorCode.KB_NOT_FOUND.getCode(),
                assertThrows(KnowledgeException.class,
                        () -> KnowledgeBaseRules.checkAccessible(null, user(9L, UserRole.USER))).getCode());
        assertEquals(ErrorCode.KB_NOT_FOUND.getCode(),
                assertThrows(KnowledgeException.class,
                        () -> KnowledgeBaseRules.checkAccessible(kb(KnowledgeBaseStatus.ACTIVE, null),
                                user(9L, UserRole.USER))).getCode());
        assertEquals(ErrorCode.UNAUTHORIZED.getCode(),
                assertThrows(KnowledgeException.class,
                        () -> KnowledgeBaseRules.checkAccessible(kb(KnowledgeBaseStatus.ACTIVE, 9L), null))
                        .getCode());
    }

    /**
     * 策略绑定开关：null 或非 0 视为开启（兼容存量数据）。
     *
     * <p>守护 {@code isStrategyBindingEnabled}：把 null 当关闭即失败（存量库被误判）。
     */
    @Test
    void strategyBindingShouldDefaultToEnabled() {
        assertTrue(KnowledgeBaseRules.isStrategyBindingEnabled(null));
        WbKnowledgeBase kb = kb(KnowledgeBaseStatus.ACTIVE, 9L);
        kb.setStrategyBindingEnabled(null);
        assertTrue(KnowledgeBaseRules.isStrategyBindingEnabled(kb));
        kb.setStrategyBindingEnabled(1);
        assertTrue(KnowledgeBaseRules.isStrategyBindingEnabled(kb));
        kb.setStrategyBindingEnabled(0);
        assertFalse(KnowledgeBaseRules.isStrategyBindingEnabled(kb));
    }

    /**
     * 构造一行知识库。
     *
     * @param status 状态（可空）
     * @param userId 归属用户（可空）
     * @return 知识库行
     */
    private WbKnowledgeBase kb(KnowledgeBaseStatus status, Long userId) {
        WbKnowledgeBase kb = new WbKnowledgeBase();
        kb.setStatus(status == null ? null : status.getCode());
        kb.setUserId(userId);
        return kb;
    }

    /**
     * 构造一个登录用户。
     *
     * @param id   用户 ID（可空，用于未登录语义）
     * @param role 角色
     * @return 登录用户
     */
    private KnowledgeUser user(Long id, UserRole role) {
        KnowledgeUser user = new KnowledgeUser();
        user.setId(id);
        user.setRole(role);
        return user;
    }
}
