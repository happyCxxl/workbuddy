package com.workbuddy.common.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.workbuddy.common.domain.base.BaseInfo;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 用户（表：kb_user）。
 *
 * @author cxxl
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("kb_user")
public class User extends BaseInfo {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（雪花） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 登录用户名 */
    private String username;

    /** 真实姓名 */
    private String displayName;

    /** 邮箱 */
    private String email;

    /** 手机号 */
    private String phone;

    /** 头像对象 key（avatar/{userId}/{随机段}.{扩展名}）；null = 未设置，界面回落姓名首字 */
    private String avatar;

    /** 头像存储类型（StorageType 枚举码）；未设置头像为空 */
    private String avatarStorageType;

    /** 头像数据源实例 ID（kb_storage_source.id）；未设置头像为空 */
    private Long avatarStorageSourceId;

    /** 头像桶名（local 下为一级子目录）；未设置头像为空 */
    private String avatarBucket;

    /** 密码（BCrypt 哈希） */
    private String password;

    /** 状态：1 启用 / 0 停用 */
    private Integer status;

    /** 角色码值：ADMIN 管理员 / USER 普通用户（见 UserRole） */
    private String role;

    /** 令牌版本：递增即让该账号已签发的令牌全部失效（改密码/改角色/停用/删除时 +1） */
    private Integer tokenVersion;
}
