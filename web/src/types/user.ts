// 用户契约（与后端 UserVO/wb_user 对齐；响应不含密码）

/** 用户状态码值（与后端 UserStatus 对齐） */
export const USER_STATUS = {
  /** 启用 */
  ENABLED: 1,
  /** 停用 */
  DISABLED: 0,
};

export interface UserVO {
  /** 雪花 ID，后端按字符串下发防精度丢失 */
  id: string;
  username: string;
  /** 真实姓名 */
  displayName: string | null;
  /** 邮箱 */
  email: string | null;
  /** 手机号 */
  phone: string | null;
  /** 头像地址（/user/avatar/{id}?v={版本段}）；未设置头像时为 null */
  avatar: string | null;
  /** 状态码值：1 启用 / 0 停用 */
  status: number;
  /** 角色码值：ADMIN 管理员 / USER 普通用户 */
  role: string | null;
  createBy: string | null;
  createTime: string | null;
  updateBy: string | null;
  updateTime: string | null;
}

/** 个人信息修改入参（本人操作；邮箱/手机号传空串表示清空） */
export interface ProfileUpdateRequest {
  displayName: string;
  email?: string;
  phone?: string;
}

/** 修改密码入参（本人操作） */
export interface PasswordUpdateRequest {
  oldPassword: string;
  newPassword: string;
}

/** 用户分页入参 */
export interface UserPageQuery {
  current: number;
  size: number;
  username?: string;
  /** 角色码值过滤：ADMIN / USER */
  role?: string;
  /** 状态过滤：1 启用 / 0 停用 */
  status?: number;
}

/** 新增用户入参（与后端 UserCreateDTO 对齐） */
export interface UserCreateRequest {
  username: string;
  displayName: string;
  email?: string;
  phone?: string;
  password: string;
  role?: string;
  status?: number;
}

/** 编辑用户入参（与后端 UserUpdateRequest 对齐；用户名不可改，密码不传表示不重置） */
export interface UserUpdateRequest {
  displayName?: string;
  email?: string;
  phone?: string;
  password?: string;
  role?: string;
  status?: number;
}
