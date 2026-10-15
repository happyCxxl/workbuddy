package com.workbuddy.auth.db;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.workbuddy.common.domain.entity.WbUser;
import com.workbuddy.infra.persistence.InfraDbService;

/**
 * 用户数据访问服务（表：wb_user）。
 *
 * @author cxxl
 */
public interface WbUserDbService extends InfraDbService<WbUser> {

    /**
     * 按用户名查启用状态用户。
     *
     * @param username 登录用户名
     * @return 用户实体；不存在或已停用/删除返回 null
     */
    WbUser findActiveByUsername(String username);

    /**
     * 判断用户名是否已被占用（含停用用户，不含已删除）。
     *
     * @param username 登录用户名
     * @return 已存在返回 true
     */
    boolean existsByUsername(String username);

    /**
     * 分页查询用户（不含已删除），支持用户名模糊、角色与状态精确过滤。
     *
     * @param current  当前页
     * @param size     每页条数
     * @param username 用户名（模糊查询，可空）
     * @param role     角色码值（精确匹配，可空）
     * @param status   状态（精确匹配，可空）
     * @return 用户分页结果
     */
    IPage<WbUser> pageByCondition(long current, long size, String username, String role, Integer status);

    /**
     * 取用户的当前令牌版本（含停用用户，不含已删除）。
     *
     * @param id 用户主键
     * @return 令牌版本；用户不存在或已删除返回 null
     */
    Integer findTokenVersion(Long id);
}
