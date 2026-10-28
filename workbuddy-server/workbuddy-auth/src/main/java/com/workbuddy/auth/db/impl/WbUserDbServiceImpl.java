package com.workbuddy.auth.db.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workbuddy.auth.db.WbUserDbService;
import com.workbuddy.common.domain.entity.WbUser;
import com.workbuddy.common.enums.user.UserStatus;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.auth.mapper.WbUserMapper;
import com.workbuddy.infra.persistence.InfraDbServiceImpl;
import org.springframework.stereotype.Service;

/**
 * 用户数据访问服务实现。
 */
@Service
public class WbUserDbServiceImpl extends InfraDbServiceImpl<WbUserMapper, WbUser> implements WbUserDbService {

    /**
     * 按用户名取处于启用状态的用户。
     *
     * @param username 用户名
     * @return 用户行；账号不存在或已停用时为 null
     */
    @Override
    public WbUser findActiveByUsername(String username) {
        LambdaQueryWrapper<WbUser> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbUser::getUsername, username)
                .eq(WbUser::getStatus, UserStatus.ENABLED.getCode());
        return getOne(queryWrapper, false);
    }

    /**
     * 用户名是否已被占用（不区分启用状态）。
     *
     * @param username 用户名
     * @return 已存在为 true
     */
    @Override
    public boolean existsByUsername(String username) {
        LambdaQueryWrapper<WbUser> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbUser::getUsername, username);
        return exists(queryWrapper);
    }

    /**
     * 按条件分页查用户，命中条件为空则不加该过滤条件。
     *
     * @param current  页码
     * @param size     每页条数
     * @param username 用户名（模糊匹配，可空）
     * @param role     角色码值（可空）
     * @param status   状态码值（可空）
     * @return 用户分页结果，按 ID 倒序
     */
    @Override
    public IPage<WbUser> pageByCondition(long current, long size, String username, String role, Integer status) {
        LambdaQueryWrapper<WbUser> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.like(StrUtil.isNotBlank(username), WbUser::getUsername, username)
                .eq(StrUtil.isNotBlank(role), WbUser::getRole, role)
                .eq(NullUtil.isNotNull(status), WbUser::getStatus, status)
                .orderByDesc(WbUser::getId);
        return page(new Page<>(current, size), queryWrapper);
    }

    /**
     * 取用户的令牌版本号（改密码等场景据此让旧令牌失效）。
     *
     * @param id 用户 ID
     * @return 令牌版本号；用户不存在时为 null
     */
    @Override
    public Integer findTokenVersion(Long id) {
        LambdaQueryWrapper<WbUser> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.select(WbUser::getTokenVersion)
                .eq(WbUser::getId, id);
        WbUser user = getOne(queryWrapper, false);
        return NullUtil.isNull(user) ? null : user.getTokenVersion();
    }
}
