import { http } from './http';
import type { PageResult } from '@/types/response';
import type {
  PasswordUpdateRequest,
  ProfileUpdateRequest,
  UserCreateRequest,
  UserPageQuery,
  UserUpdateRequest,
  UserVO,
} from '@/types/user';

/** 查询个人信息（本人） */
export async function getProfile(): Promise<UserVO> {
  const response = await http.get<UserVO>('/user/profile');
  return response.data;
}

/** 修改个人信息（本人；只改真实姓名、邮箱、手机号） */
export async function updateProfile(request: ProfileUpdateRequest): Promise<void> {
  await http.put<void>('/user/profile', request);
}

/** 修改密码（本人）：返回以新令牌版本补签的令牌（本地令牌需替换为新值） */
export async function updatePassword(request: PasswordUpdateRequest): Promise<string> {
  const response = await http.put<string>('/user/password', request);
  return response.data;
}

/** 上传头像（本人）：每次上传都换一个新地址，返回值可直接渲染 */
export async function updateAvatar(file: File): Promise<string> {
  const formData = new FormData();
  formData.append('file', file);
  const response = await http.post<string>('/user/avatar', formData);
  return response.data;
}

/** 移除头像（本人）：库里清空，界面回落姓名首字 */
export async function deleteAvatar(): Promise<void> {
  await http.delete<void>('/user/avatar');
}

/** 分页查询用户（仅管理员；响应不含密码） */
export async function getUserPage(query: UserPageQuery): Promise<PageResult<UserVO>> {
  const response = await http.get<PageResult<UserVO>>('/user/page', { params: query });
  return response.data;
}

/** 新增用户（仅管理员） */
export async function addUserByAdmin(request: UserCreateRequest): Promise<string> {
  const response = await http.post<string>('/user/add', request);
  return response.data;
}

/** 编辑用户（仅管理员；用户名不可改，密码不传表示不重置） */
export async function updateUser(id: string, request: UserUpdateRequest): Promise<void> {
  await http.put<void>(`/user/${id}`, request);
}

/** 删除用户（仅管理员；逻辑删除） */
export async function deleteUser(id: string): Promise<void> {
  await http.delete<void>(`/user/${id}`);
}
