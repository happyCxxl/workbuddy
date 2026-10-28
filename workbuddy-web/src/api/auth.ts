import { http } from './http';
import type { LoginRequest, LoginVO, RegisterRequest } from '@/types/auth';

// 认证接口层（与后端 AuthController 对应）：登录换取访问令牌
export async function getToken(request: LoginRequest): Promise<LoginVO> {
  const response = await http.post<LoginVO>('/auth/login', request);
  return response.data;
}

// 认证接口层（与后端 AuthController 对应）：注册账号
export async function addUser(request: RegisterRequest): Promise<null> {
  const response = await http.post<null>('/auth/register', request);
  return response.data;
}
