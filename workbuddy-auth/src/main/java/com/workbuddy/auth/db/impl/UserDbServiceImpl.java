package com.workbuddy.auth.db.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workbuddy.auth.db.UserDbService;
import com.workbuddy.common.domain.entity.User;
import com.workbuddy.common.enums.user.UserStatus;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.auth.mapper.UserMapper;
import com.workbuddy.infra.persistence.InfraDbServiceImpl;
import org.springframework.stereotype.Service;

/**
 * 用户数据访问服务实现。
 *
 * @author cxxl
 */
@Service
public class UserDbServiceImpl extends InfraDbServiceImpl<UserMapper, User> implements UserDbService {

    @Override
    public User findActiveByUsername(String username) {
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getUsername, username)
                .eq(User::getStatus, UserStatus.ENABLED.getCode());
        return getOne(queryWrapper, false);
    }

    @Override
    public boolean existsByUsername(String username) {
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getUsername, username);
        return exists(queryWrapper);
    }

    @Override
    public IPage<User> pageByCondition(long current, long size, String username, String role, Integer status) {
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.like(StrUtil.isNotBlank(username), User::getUsername, username)
                .eq(StrUtil.isNotBlank(role), User::getRole, role)
                .eq(NullUtil.isNotNull(status), User::getStatus, status)
                .orderByDesc(User::getId);
        return page(new Page<>(current, size), queryWrapper);
    }

    @Override
    public Integer findTokenVersion(Long id) {
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.select(User::getTokenVersion)
                .eq(User::getId, id);
        User user = getOne(queryWrapper, false);
        return NullUtil.isNull(user) ? null : user.getTokenVersion();
    }
}
