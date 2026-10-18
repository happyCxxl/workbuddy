package com.workbuddy.common.domain.rules;

import cn.hutool.core.util.ObjectUtil;
import com.workbuddy.common.domain.entity.WbKnowledgeBase;
import com.workbuddy.common.enums.knowledge.KnowledgeBaseStatus;
import com.workbuddy.common.enums.knowledge.StrategyBindingSwitch;
import com.workbuddy.common.enums.user.UserRole;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.security.KnowledgeUser;
import com.workbuddy.common.utils.NullUtil;

/**
 * 知识库规则：状态两态（启用/停用）迁移校验 + 可见范围（归属）。
 */
public final class KnowledgeBaseRules {

    private KnowledgeBaseRules() {
    }

    /**
     * 停用校验：仅启用状态（ACTIVE=1）可停用。
     *
     * @param kb 知识库实体
     * @throws KnowledgeException 非启用状态（KB_STATUS_ILLEGAL 40402）
     */
    public static void checkCanDisable(WbKnowledgeBase kb) {
        requireStatus(kb, KnowledgeBaseStatus.ACTIVE, ErrorCode.KB_STATUS_ILLEGAL, "仅启用状态的知识库可以停用");
    }

    /**
     * 启用校验：仅停用状态（DISABLED=0）可启用。
     *
     * @param kb 知识库实体
     * @throws KnowledgeException 非停用状态（KB_STATUS_ILLEGAL 40402）
     */
    public static void checkCanEnable(WbKnowledgeBase kb) {
        requireStatus(kb, KnowledgeBaseStatus.DISABLED, ErrorCode.KB_STATUS_ILLEGAL, "仅停用状态的知识库可以启用");
    }

    /**
     * 提交校验：仅启用状态的知识库可接收文档提交。
     *
     * @param kb 知识库实体
     * @throws KnowledgeException 非启用状态（KB_NOT_ACTIVE 40421）
     */
    public static void checkCanSubmit(WbKnowledgeBase kb) {
        requireStatus(kb, KnowledgeBaseStatus.ACTIVE, ErrorCode.KB_NOT_ACTIVE, null);
    }

    /**
     * 可见范围：当前登录用户查询时要加的"归属 ID"。
     *
     * <p>管理员不限归属（返回 null，表示查询不加归属条件）；普通用户只能看到自己创建的库。
     * 返回值 null 与"当前用户为 null"是两件事 —— **无登录上下文一律拒绝**，
     * 不允许把"没登录"退化成"不过滤"，那样一个缺失的认证上下文就等于全量可见。
     *
     * @param user 当前登录用户
     * @return 普通用户返回其用户 ID；管理员返回 null（不过滤）
     * @throws KnowledgeException 无登录上下文（UNAUTHORIZED 40101）
     */
    public static Long visibleOwnerId(KnowledgeUser user) {
        requireLogin(user);
        return isAdmin(user) ? null : user.getId();
    }

    /**
     * 归属校验：当前用户能否读写该知识库。
     *
     * <p>管理员放行全部，普通用户仅限自己创建的（`wb_knowledge_base.user_id` 与本人一致）；
     * 存量 `user_id = NULL` 的数据对普通用户不可见（知识库归属创建者，无归属的行不视为公共资产）。
     *
     * <p>不可访问时抛 {@link ErrorCode#KB_NOT_FOUND}：越权访问与库不存在在响应上完全一致，
     * 不把别人的库 ID 变成可探测的信息。
     *
     * @param kb   知识库实体
     * @param user 当前登录用户
     * @throws KnowledgeException 无登录上下文（UNAUTHORIZED 40101）或不可访问（KB_NOT_FOUND 40401）
     */
    public static void checkAccessible(WbKnowledgeBase kb, KnowledgeUser user) {
        requireLogin(user);
        if (isAdmin(user)) {
            return;
        }
        ThrowUtil.throwIf(NullUtil.isNull(kb) || NullUtil.isNull(kb.getUserId())
                || !ObjectUtil.equal(kb.getUserId(), user.getId()), ErrorCode.KB_NOT_FOUND);
    }

    /** 登录上下文校验：缺失（含用户 ID 为空）即拒绝，避免退化成"不过滤" */
    private static void requireLogin(KnowledgeUser user) {
        ThrowUtil.throwIf(NullUtil.isNull(user) || NullUtil.isNull(user.getId()), ErrorCode.UNAUTHORIZED);
    }

    /** 是否管理员（角色缺失时按普通用户处理，取最保守语义） */
    private static boolean isAdmin(KnowledgeUser user) {
        return NullUtil.isNotNull(user) && UserRole.ADMIN == user.getRole();
    }

    /** 状态闸门公共实现：状态缺失或非预期即抛对应错误码 */
    private static void requireStatus(WbKnowledgeBase kb, KnowledgeBaseStatus expected, ErrorCode errorCode,
                                      String message) {
        if (NullUtil.isNull(kb.getStatus()) || !ObjectUtil.equal(kb.getStatus(), expected.getCode())) {
            if (NullUtil.isNull(message)) {
                throw new KnowledgeException(errorCode);
            }
            throw new KnowledgeException(errorCode, message);
        }
    }

    /**
     * 策略绑定开关判定：null 或非 0 视为开启（兼容存量数据）。
     */
    public static boolean isStrategyBindingEnabled(WbKnowledgeBase kb) {
        return NullUtil.isNull(kb) || StrategyBindingSwitch.isOn(kb.getStrategyBindingEnabled());
    }
}
