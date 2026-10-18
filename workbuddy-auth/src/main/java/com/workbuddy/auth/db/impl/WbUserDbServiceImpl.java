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

    @Override
    public WbUser findActiveByUsername(String username) {
        LambdaQueryWrapper<WbUser> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbUser::getUsername, username)
                .eq(WbUser::getStatus, UserStatus.ENABLED.getCode());
        return getOne(queryWrapper, false);
    }

    @Override
    public boolean existsByUsername(String username) {
        LambdaQueryWrapper<WbUser> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbUser::getUsername, username);
        return exists(queryWrapper);
    }

    @Override
    public IPage<WbUser> pageByCondition(long current, long size, String username, String role, Integer status) {
        LambdaQueryWrapper<WbUser> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.like(StrUtil.isNotBlank(username), WbUser::getUsername, username)
                .eq(StrUtil.isNotBlank(role), WbUser::getRole, role)
                .eq(NullUtil.isNotNull(status), WbUser::getStatus, status)
                .orderByDesc(WbUser::getId);
        return page(new Page<>(current, size), queryWrapper);
    }

    @Override
    public Integer findTokenVersion(Long id) {
        LambdaQueryWrapper<WbUser> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.select(WbUser::getTokenVersion)
                .eq(WbUser::getId, id);
        WbUser user = getOne(queryWrapper, false);
        return NullUtil.isNull(user) ? null : user.getTokenVersion();
    }
}
