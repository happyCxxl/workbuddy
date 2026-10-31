import { computed, ref } from 'vue';
import { defineStore } from 'pinia';
import type { LoginVO, UserRole } from '@/types/auth';

export type { UserRole };

const USER_KEY = 'knowledge-user';

/** 当前用户快照：登录响应显式下发；令牌本身在 HttpOnly Cookie 里，脚本不可读 */
export interface AuthUser {
  id: string | null;
  username: string | null;
  displayName: string | null;
  email: string | null;
  phone: string | null;
  /** 头像地址；null = 未设置，界面回落姓名首字 */
  avatar: string | null;
  status: number | null;
  role: UserRole;
}

function clearStored(): void {
  for (const storage of [localStorage, sessionStorage]) {
    storage.removeItem(USER_KEY);
  }
}

/** 快照可能缺失、来自旧版本或已损坏，这里逐字段收敛类型 */
function normalizeUser(raw: unknown): AuthUser {
  const source: Record<string, unknown> =
    raw !== null && typeof raw === 'object' ? (raw as Record<string, unknown>) : {};
  return {
    id: typeof source.id === 'string' ? source.id : null,
    username: typeof source.username === 'string' ? source.username : null,
    displayName: typeof source.displayName === 'string' ? source.displayName : null,
    email: typeof source.email === 'string' ? source.email : null,
    phone: typeof source.phone === 'string' ? source.phone : null,
    avatar: typeof source.avatar === 'string' ? source.avatar : null,
    status: typeof source.status === 'number' ? source.status : null,
    role: source.role === 'ADMIN' ? 'ADMIN' : 'USER',
  };
}

/** 读取用户快照；缺失或损坏时返回 null */
function readUser(): AuthUser | null {
  const raw = localStorage.getItem(USER_KEY) ?? sessionStorage.getItem(USER_KEY);
  if (raw === null) {
    return null;
  }
  try {
    return normalizeUser(JSON.parse(raw));
  } catch {
    return null;
  }
}

/** 快照所在的那一档存储；两边都没有时回落 localStorage */
function userStorage(): Storage {
  return localStorage.getItem(USER_KEY) === null ? sessionStorage : localStorage;
}

// 登录态：令牌在 HttpOnly Cookie 中；这里只持久化用户快照（记住我 → localStorage，否则 sessionStorage）
export const useAuthStore = defineStore('auth', () => {
  const user = ref<AuthUser | null>(readUser());

  const isLoggedIn = computed(() => user.value !== null);

  const username = computed(() => user.value?.username ?? null);
  // 用于识别「当前账号自己」（用户管理页判断是否在编辑自己）
  const userId = computed(() => user.value?.id ?? null);
  const displayName = computed(() => user.value?.displayName ?? null);
  const avatar = computed(() => user.value?.avatar ?? null);
  const status = computed(() => user.value?.status ?? null);
  const role = computed<UserRole>(() => user.value?.role ?? 'USER');
  const isAdmin = computed(() => role.value === 'ADMIN');

  /** 登录成功：只落用户快照；令牌由服务端写入 HttpOnly Cookie */
  function setLogin(vo: LoginVO, remember: boolean): void {
    const snapshot = normalizeUser(vo);
    user.value = snapshot;
    const target = remember ? localStorage : sessionStorage;
    const other = remember ? sessionStorage : localStorage;
    target.setItem(USER_KEY, JSON.stringify(snapshot));
    other.removeItem(USER_KEY);
  }

  /** 更新本地展示的姓名（个人信息保存后调用，不必重新登录） */
  function setDisplayName(name: string | null): void {
    if (user.value === null) {
      return;
    }
    user.value = { ...user.value, displayName: name };
    userStorage().setItem(USER_KEY, JSON.stringify(user.value));
  }

  /** 更新本地头像（上传或移除后调用） */
  function setAvatar(url: string | null): void {
    if (user.value === null) {
      return;
    }
    user.value = { ...user.value, avatar: url };
    userStorage().setItem(USER_KEY, JSON.stringify(user.value));
  }

  function clearToken(): void {
    user.value = null;
    clearStored();
  }

  return {
    isLoggedIn,
    user,
    username,
    userId,
    displayName,
    avatar,
    status,
    role,
    isAdmin,
    setLogin,
    setDisplayName,
    setAvatar,
    clearToken,
  };
});
