// 认证契约（与后端 LoginDTO/LoginVO/RegisterDTO 对齐）

/** 角色码值：与后端 UserRole 对齐 */
export type UserRole = 'ADMIN' | 'USER';

export interface LoginRequest {
  username: string;
  password: string;
  /** 记住我：true 换长期令牌，false 换短期令牌 */
  remember: boolean;
}

/** 登录结果：用户信息由后端显式下发 */
export interface LoginVO {
  /** 主键（雪花 ID，后端按字符串下发） */
  id: string;
  username: string;
  displayName: string | null;
  email: string | null;
  phone: string | null;
  /** 状态：1 启用 / 0 停用 */
  status: number;
  /** 头像地址；未设置头像时为 null，界面回落姓名首字 */
  avatar: string | null;
  role: UserRole;
  /** 令牌版本：递增即让该账号已签发的令牌全部失效 */
  tokenVersion: number;
  /** 访问令牌 */
  token: string;
}

export interface RegisterRequest {
  username: string;
  /** 真实姓名 */
  displayName: string;
  /** 邮箱（可空） */
  email?: string;
  /** 手机号（可空） */
  phone?: string;
  password: string;
}
