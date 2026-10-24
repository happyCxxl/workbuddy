package com.workbuddy.auth.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.workbuddy.common.dto.request.user.UserQueryDTO;
import com.workbuddy.auth.db.WbUserDbService;
import com.workbuddy.auth.service.UserService;
import com.workbuddy.auth.support.JwtTokenSupport;
import com.workbuddy.common.domain.entity.WbUser;
import com.workbuddy.common.dto.request.user.PasswordUpdateDTO;
import com.workbuddy.common.dto.request.user.ProfileUpdateDTO;
import com.workbuddy.common.dto.request.user.UserCreateDTO;
import com.workbuddy.common.dto.request.user.UserUpdateDTO;
import com.workbuddy.common.dto.response.user.UserVO;
import com.workbuddy.common.utils.AvatarUrlUtil;
import com.workbuddy.common.enums.knowledge.AuditActionType;
import com.workbuddy.common.enums.knowledge.AuditObjectType;
import com.workbuddy.common.enums.user.UserRole;
import com.workbuddy.common.enums.user.UserStatus;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.security.KnowledgeUser;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.common.utils.SecurityUtil;
import com.workbuddy.common.security.audit.AuditEventPublisher;
import com.workbuddy.common.utils.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;

/**
 * 用户服务实现。
 *
 * <p>管理员维护类操作的守卫：用户名唯一、角色合法、不得操作自身、
 * 不得让系统失去最后一个启用的管理员。
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    /** 审计对象类型：用户账号 */
    private static final String AUDIT_OBJECT_TYPE = AuditObjectType.KB_USER.key();

    /** 补签令牌的有效期下限：改密后剩余时间过短时按它下发 */
    private static final Duration MIN_SESSION_TTL = Duration.ofHours(1);

    private final WbUserDbService userDbService;

    private final PasswordEncoder passwordEncoder;

    private final AuditEventPublisher auditEventPublisher;

    private final JwtTokenSupport jwtUtil;

    /**
     * 分页查用户。
     *
     * @param query 查询条件
     * @return 用户分页；敏感字段不进入响应体
     */
    @Override
    public IPage<UserVO> pageUsers(UserQueryDTO query) {
        IPage<WbUser> source = userDbService.pageByCondition(query.currentOrDefault(), query.sizeOrDefault(),
                query.getUsername(), query.getRole(), query.getStatus());
        // 显式字段映射：密码等敏感字段不进入响应体
        return source.convert(UserServiceImpl::toUserVO);
    }

    /**
     * 新增用户。
     *
     * @param request 新增用户请求
     * @return 新用户 ID
     * @throws KnowledgeException 用户名已存在（USERNAME_EXISTS）
     */
    @Override
    public Long addUser(UserCreateDTO request) {
        ThrowUtil.throwIf(NullUtil.isNotNull(userDbService.findActiveByUsername(request.getUsername())),
                ErrorCode.USERNAME_EXISTS);
        WbUser user = new WbUser();
        user.setUsername(request.getUsername());
        user.setDisplayName(request.getDisplayName());
        user.setEmail(StrUtil.trimToNull(request.getEmail()));
        user.setPhone(StrUtil.trimToNull(request.getPhone()));
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRole(parseRole(request.getRole()).getCode());
        user.setStatus(NullUtil.isNull(request.getStatus())
                ? Integer.valueOf(UserStatus.ENABLED.getCode()) : parseStatus(request.getStatus()));
        userDbService.save(user);
        auditEventPublisher.publish(AuditActionType.USER_CREATE, AUDIT_OBJECT_TYPE, user.getId(),
                JsonUtil.toJsonStr(auditSummary(user)));
        return user.getId();
    }

    /**
     * 修改用户：不允许改自己的角色与状态，避免把自己锁在外面。
     *
     * @param id      用户 ID
     * @param request 修改请求
     */
    @Override
    public void updateUser(Long id, UserUpdateDTO request) {
        WbUser user = userDbService.getById(id);
        ThrowUtil.throwIf(NullUtil.isNull(user), ErrorCode.USER_NOT_FOUND);
        Map<String, String> beforeSummary = auditSummary(user);
        // 自保护：不允许改自己的角色或状态，避免管理员把自己锁在外面
        boolean self = isSelf(id);
        boolean roleChanged = NullUtil.isNotNull(request.getRole());
        boolean statusChanged = NullUtil.isNotNull(request.getStatus());
        ThrowUtil.throwIf(self && (roleChanged || statusChanged), ErrorCode.USER_SELF_OPERATION_FORBIDDEN);

        // 解析目标值并校验合法（非法码值/状态不落库）
        UserRole targetRole = roleChanged ? parseRole(request.getRole())
                : UserRole.of(user.getRole());
        Integer targetStatus = statusChanged ? parseStatus(request.getStatus()) : user.getStatus();

        // 变更后不再保留「启用的管理员」时，要求系统还有其他启用的管理员。
        // 三种路径都要堵：停用、降级为普通用户、删除（删除见 deleteUser）
        boolean losingEnabledAdmin = isEnabledAdmin(user)
                && (!isEnabledAdmin(targetRole.getCode(), targetStatus));
        ThrowUtil.throwIf(losingEnabledAdmin && countOtherEnabledAdmins(id) == 0,
                ErrorCode.LAST_ADMIN_FORBIDDEN);

        boolean passwordChanged = StrUtil.isNotBlank(request.getPassword());
        if (roleChanged) {
            user.setRole(targetRole.getCode());
        }
        if (statusChanged) {
            user.setStatus(targetStatus);
        }
        if (passwordChanged) {
            user.setPassword(passwordEncoder.encode(request.getPassword()));
        }
        // 资料字段：不传表示不变，传空串表示清空（真实姓名的空值已由校验拦下）
        if (NullUtil.isNotNull(request.getDisplayName())) {
            user.setDisplayName(request.getDisplayName());
        }
        if (NullUtil.isNotNull(request.getEmail())) {
            user.setEmail(StrUtil.trimToNull(request.getEmail()));
        }
        if (NullUtil.isNotNull(request.getPhone())) {
            user.setPhone(StrUtil.trimToNull(request.getPhone()));
        }
        // 凭据或权限变更：递增令牌版本，让该账号已签发的令牌立即失效
        if (roleChanged || statusChanged || passwordChanged) {
            user.setTokenVersion(nextTokenVersion(user));
        }
        userDbService.updateById(user);
        auditEventPublisher.publish(resolveUpdateAction(roleChanged, statusChanged, targetStatus),
                AUDIT_OBJECT_TYPE, id, diffPayload(beforeSummary, auditSummary(user)));
    }

    /**
     * 删除用户：不允许删自己，也不允许删掉最后一个启用的管理员。
     *
     * @param id 用户 ID
     */
    @Override
    public void deleteUser(Long id) {
        WbUser user = userDbService.getById(id);
        ThrowUtil.throwIf(NullUtil.isNull(user), ErrorCode.USER_NOT_FOUND);
        // 自保护：不允许删除自己
        ThrowUtil.throwIf(isSelf(id), ErrorCode.USER_SELF_OPERATION_FORBIDDEN);
        // 删除启用中的管理员前确认系统仍有其他启用的管理员
        if (isEnabledAdmin(user)) {
            ThrowUtil.throwIf(countOtherEnabledAdmins(id) == 0, ErrorCode.LAST_ADMIN_FORBIDDEN);
        }
        userDbService.removeById(id);
        auditEventPublisher.publish(AuditActionType.USER_DELETE, AUDIT_OBJECT_TYPE, id,
                JsonUtil.toJsonStr(auditSummary(user)));
    }

    /**
     * 取用户档案。
     *
     * @param id 用户 ID
     * @return 用户档案
     */
    @Override
    public UserVO getUserProfile(Long id) {
        WbUser user = userDbService.getById(id);
        ThrowUtil.throwIf(NullUtil.isNull(user), ErrorCode.USER_NOT_FOUND);
        return toUserVO(user);
    }

    /**
     * 修改个人档案：只允许改展示名、邮箱与手机号。
     *
     * @param id      用户 ID
     * @param request 档案修改请求
     */
    @Override
    public void updateProfile(Long id, ProfileUpdateDTO request) {
        WbUser user = userDbService.getById(id);
        ThrowUtil.throwIf(NullUtil.isNull(user), ErrorCode.USER_NOT_FOUND);
        Map<String, String> beforeSummary = auditSummary(user);
        user.setDisplayName(request.getDisplayName());
        user.setEmail(StrUtil.trimToNull(request.getEmail()));
        user.setPhone(StrUtil.trimToNull(request.getPhone()));
        userDbService.updateById(user);
        auditEventPublisher.publish(AuditActionType.USER_UPDATE, AUDIT_OBJECT_TYPE, id,
                diffPayload(beforeSummary, auditSummary(user)));
    }

    /**
     * 改密码：校验旧口令，新口令不得与旧口令相同；成功后递增令牌版本，旧令牌全部失效。
     *
     * @param id      用户 ID
     * @param request 改密请求
     * @return 用新凭据重新签发的令牌
     */
    @Override
    public String changePassword(Long id, PasswordUpdateDTO request) {
        WbUser user = userDbService.getById(id);
        ThrowUtil.throwIf(NullUtil.isNull(user), ErrorCode.USER_NOT_FOUND);
        ThrowUtil.throwIf(!passwordEncoder.matches(request.getOldPassword(), user.getPassword()),
                ErrorCode.OLD_PASSWORD_MISMATCH);
        ThrowUtil.throwIf(passwordEncoder.matches(request.getNewPassword(), user.getPassword()),
                ErrorCode.PARAM_INVALID, "新密码不能与当前密码相同");
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        // 凭据变更：递增令牌版本，该账号此前签发的令牌（含本次请求用的那张）全部失效
        user.setTokenVersion(nextTokenVersion(user));
        userDbService.updateById(user);
        auditEventPublisher.publish(AuditActionType.USER_PASSWORD_CHANGE, AUDIT_OBJECT_TYPE, id, null);
        // 以新版本补签令牌，当前会话不必重新登录；有效期沿用本会话的剩余时长
        return jwtUtil.sign(user.getId(), user.getUsername(), UserRole.of(user.getRole()),
                user.getTokenVersion(), remainingTtl());
    }

    /**
     * 更新动作归类：只改状态时记「启用 / 停用」，其余（改角色、重置密码、两者混合）记为更新。
     * 密码变更不落任何明文或哈希，摘要只体现角色与状态。
     */
    private AuditActionType resolveUpdateAction(boolean roleChanged, boolean statusChanged, Integer targetStatus) {
        if (!roleChanged && statusChanged) {
            return UserStatus.isEnabled(targetStatus)
                    ? AuditActionType.USER_ENABLE
                    : AuditActionType.USER_DISABLE;
        }
        return AuditActionType.USER_UPDATE;
    }

    /** 审计摘要字段：只带出 id/用户名/角色/状态与令牌版本，绝不包含密码 */
    private Map<String, String> auditSummary(WbUser user) {
        return Map.of(
                "id", NullUtil.isNull(user.getId()) ? "" : String.valueOf(user.getId()),
                "username", StrUtil.nullToEmpty(user.getUsername()),
                "role", StrUtil.nullToEmpty(user.getRole()),
                "status", NullUtil.isNull(user.getStatus()) ? "" : String.valueOf(user.getStatus()),
                "tokenVersion", NullUtil.isNull(user.getTokenVersion()) ? "" : String.valueOf(user.getTokenVersion()));
    }

    /** 修改类审计载荷：把改前/改后摘要合成一个 JSON 对象 */
    private String diffPayload(Map<String, String> before, Map<String, String> after) {
        return JsonUtil.toJsonStr(Map.of("before", before, "after", after));
    }

    /** 角色解析：非法码值直接拒绝，避免落库脏数据 */
    private UserRole parseRole(String code) {
        if (StrUtil.isBlank(code)) {
            return UserRole.USER;
        }
        boolean valid = Arrays.stream(UserRole.values())
                .anyMatch(role -> role.getCode().equalsIgnoreCase(code.trim()));
        ThrowUtil.throwIf(!valid, ErrorCode.ROLE_INVALID);
        return UserRole.of(code);
    }

    /** 状态校验：只接受 1 启用 / 0 停用，其他值拒绝落库 */
    private Integer parseStatus(Integer status) {
        ThrowUtil.throwIf(NullUtil.isNull(UserStatus.of(status)), ErrorCode.USER_STATUS_INVALID);
        return status;
    }

    /** 目标用户是否为启用状态的管理员 */
    private boolean isEnabledAdmin(WbUser user) {
        return isEnabledAdmin(user.getRole(), user.getStatus());
    }

    /** 指定「角色 + 状态」组合是否构成一个启用的管理员 */
    private boolean isEnabledAdmin(String role, Integer status) {
        return UserRole.ADMIN.getCode().equalsIgnoreCase(role)
                && UserStatus.isEnabled(status);
    }

    /** 除指定用户外，仍在启用状态的管理员数量 */
    private long countOtherEnabledAdmins(Long excludeId) {
        LambdaQueryWrapper<WbUser> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbUser::getRole, UserRole.ADMIN.getCode())
                .eq(WbUser::getStatus, UserStatus.ENABLED.getCode())
                .ne(WbUser::getId, excludeId);
        return userDbService.count(queryWrapper);
    }

    /** 递增令牌版本：null 视为 0（与库表默认值一致） */
    private int nextTokenVersion(WbUser user) {
        return (NullUtil.isNull(user.getTokenVersion()) ? 0 : user.getTokenVersion()) + 1;
    }

    /** 本会话令牌的剩余时长；无令牌上下文或剩余不足下限时按下限，避免改完密码立刻掉线 */
    private Duration remainingTtl() {
        KnowledgeUser current = SecurityUtil.getUser();
        Instant expiresAt = NullUtil.isNull(current) ? null : current.getExpiresAt();
        if (NullUtil.isNull(expiresAt)) {
            return MIN_SESSION_TTL;
        }
        Duration remaining = Duration.between(Instant.now(), expiresAt);
        return remaining.compareTo(MIN_SESSION_TTL) < 0 ? MIN_SESSION_TTL : remaining;
    }

    /** 当前登录用户是否就是目标用户 */
    private boolean isSelf(Long id) {
        KnowledgeUser current = SecurityUtil.getUser();
        return NullUtil.isNotNull(current) && NullUtil.isNotNull(current.getId()) && current.getId().equals(id);
    }

    /** 实体转视图对象：只带出可对外字段，避免密码泄漏 */
    private static UserVO toUserVO(WbUser user) {
        UserVO vo = new UserVO();
        BeanUtil.copyProperties(user, vo);
        // 库里存的是对象存储的公开 URL，原样下发；历史对象 key 由 urlOrNull 归一为 null
        vo.setAvatar(AvatarUrlUtil.urlOrNull(user.getAvatar()));
        return vo;
    }
}
