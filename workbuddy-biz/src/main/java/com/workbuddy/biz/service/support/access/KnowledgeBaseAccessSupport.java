package com.workbuddy.biz.service.support.access;

import com.workbuddy.biz.service.db.WbKnowledgeBaseDbService;
import com.workbuddy.common.domain.entity.WbKnowledgeBase;
import com.workbuddy.common.domain.rules.KnowledgeBaseRules;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.security.KnowledgeUser;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.common.utils.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 知识库归属与可见范围助手：按 ID 取「当前用户可访问的」知识库、解析可见 owner、取当前用户。
 *
 * <p>所有按知识库 ID 的读写都必须先过 {@link #getAccessibleById(Long)}：存在性 + 归属一次完成，
 * 不可访问时按「不存在」处理（40401），不把别人的库 ID 变成可探测的信息。
 * 分页与统计的可见范围统一取 {@link #visibleOwnerId()}，普通用户只看自己创建的库，管理员不限归属。
 */
@Component
@RequiredArgsConstructor
public class KnowledgeBaseAccessSupport {

    private final WbKnowledgeBaseDbService knowledgeBaseDbService;

    /**
     * 按 ID 取「当前用户可访问的」知识库：存在性校验 + 归属校验一次完成。
     *
     * <p>本助手是知识库按 ID 读写的统一入口，漏走一次就是一个越权口子：
     * 详情漏了等于全部可读，更新/停用/删除漏了等于全部可改，而且不会报错，只会静默放行。
     *
     * @param id 知识库 ID
     * @return 知识库实体
     * @throws KnowledgeException 未认证（UNAUTHORIZED 40101）、不存在/已删除或不可访问（KB_NOT_FOUND 40401）
     */
    public WbKnowledgeBase getAccessibleById(Long id) {
        WbKnowledgeBase kb = knowledgeBaseDbService.getActiveById(id);
        KnowledgeBaseRules.checkAccessible(kb, SecurityUtil.getUser());
        return kb;
    }

    /**
     * 当前用户的知识库可见范围（查询条件用）。
     *
     * @return 普通用户返回自己的用户 ID；管理员返回 null（不过滤）
     * @throws KnowledgeException 无登录上下文（UNAUTHORIZED 40101）
     */
    public Long visibleOwnerId() {
        return KnowledgeBaseRules.visibleOwnerId(SecurityUtil.getUser());
    }

    /**
     * 当前登录用户 ID（**必须有值**）。
     *
     * <p>知识库必须带归属：可见范围按 `user_id` 划分，落一条没有归属的库等于建了一条普通用户
     * 谁也看不见、只有管理员能碰的孤儿数据。这里无认证上下文直接拒绝，不静默落 NULL。
     *
     * @return 当前用户 ID
     * @throws KnowledgeException 未认证（UNAUTHORIZED 40101）
     */
    public Long requireCurrentUserId() {
        KnowledgeUser user = SecurityUtil.getUser();
        ThrowUtil.throwIf(NullUtil.isNull(user) || NullUtil.isNull(user.getId()), ErrorCode.UNAUTHORIZED);
        return user.getId();
    }

    /**
     * 当前登录用户 ID（无登录态返回 null）——用于提交链路的落库字段，不做认证拦截。
     *
     * <p>与 {@link #requireCurrentUserId()} 的区别：那个是「必须取到，取不到就拒绝」（创建知识库用），
     * 这个是「取不到就留空」（提交记录/来源文件的 user_id 允许为空）。
     *
     * @return 当前用户 ID；无登录态时为 null
     */
    public Long currentUserId() {
        KnowledgeUser user = SecurityUtil.getUser();
        return NullUtil.isNull(user) ? null : user.getId();
    }
}
