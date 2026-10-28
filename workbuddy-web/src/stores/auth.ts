import { computed, ref } from 'vue';
import { defineStore } from 'pinia';
import type { LoginVO, UserRole } from '@/types/auth';

export type { UserRole };

const TOKEN_KEY = 'knowledge-token';
const USER_KEY = 'knowledge-user';

/** 当前用户快照：登录响应显式下发，随令牌存同一档存储 */
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

function readToken(): string | null {
  return localStorage.getItem(TOKEN_KEY) ?? sessionStorage.getItem(TOKEN_KEY);
}

/** 令牌当前所在的那一档存储；两边都没有时回落 localStorage */
function tokenStorage(): Storage {
  return localStorage.getItem(TOKEN_KEY) === null ? sessionStorage : localStorage;
}

function clearStored(): void {
  for (const storage of [localStorage, sessionStorage]) {
    storage.removeItem(TOKEN_KEY);
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

/** 读取随令牌持久化的用户快照；缺失或损坏时返回 null */
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

/** 令牌是否可用：仅当存在、可解析、且未过期时才算已登录。
 *
 * <p>过期令牌直接清除。
 *
 * <p>过期时刻只能从令牌本身取：刷新页面后除令牌外没有别的凭据可用。
 */
function isTokenUsable(token: string | null): boolean {
  const expiresAt = readNumberClaim(token, 'exp');
  if (expiresAt === null) {
    return false;
  }
  // exp 是秒级时间戳（JWT 规范），换算成毫秒与当前时间比较
  return expiresAt * 1000 > Date.now();
}

/** 丢弃不可用令牌与其用户快照，避免"看似已登录" */
function dropUnusableToken(): string | null {
  const token = readToken();
  if (token === null || isTokenUsable(token)) {
    return token;
  }
  clearStored();
  return null;
}

// 登录态：令牌与用户快照持久化（记住我 → localStorage，未记住我 → sessionStorage）
export const useAuthStore = defineStore('auth', () => {
  const initialToken = dropUnusableToken();
  const token = ref<string | null>(initialToken);
  // 无可用令牌时不展示残留用户信息
  const user = ref<AuthUser | null>(initialToken === null ? null : readUser());

  const isLoggedIn = computed(() => Boolean(token.value));

  // 供个人中心展示：令牌到期时刻，以及「记住我」档位（档位就是令牌当前所在的那一档存储）
  const expiresAt = computed(() => {
    const seconds = readNumberClaim(token.value, 'exp');
    return seconds === null ? null : new Date(seconds * 1000);
  });
  const rememberTier = computed<'LONG' | 'SHORT'>(() =>
    localStorage.getItem(TOKEN_KEY) === null ? 'SHORT' : 'LONG',
  );

  const username = computed(() => user.value?.username ?? null);
  // 用于识别「当前账号自己」（用户管理页判断是否在编辑自己）
  const userId = computed(() => user.value?.id ?? null);
  const displayName = computed(() => user.value?.displayName ?? null);
  const avatar = computed(() => user.value?.avatar ?? null);
  const status = computed(() => user.value?.status ?? null);
  const role = computed<UserRole>(() => user.value?.role ?? 'USER');
  const isAdmin = computed(() => role.value === 'ADMIN');

  /** 登录成功：令牌与用户快照写入同一档存储，避免"关了浏览器还剩半份状态" */
  function setLogin(vo: LoginVO, remember: boolean): void {
    const snapshot = normalizeUser(vo);
    token.value = vo.token;
    user.value = snapshot;
    const target = remember ? localStorage : sessionStorage;
    const other = remember ? sessionStorage : localStorage;
    target.setItem(TOKEN_KEY, vo.token);
    target.setItem(USER_KEY, JSON.stringify(snapshot));
    other.removeItem(TOKEN_KEY);
    other.removeItem(USER_KEY);
  }

  /** 更新本地展示的姓名（个人信息保存后调用，不必重新登录） */
  function setDisplayName(name: string | null): void {
    if (user.value === null) {
      return;
    }
    user.value = { ...user.value, displayName: name };
    tokenStorage().setItem(USER_KEY, JSON.stringify(user.value));
  }

  /** 更新本地头像（上传或移除后调用）：与令牌同档落盘，不必重新登录 */
  function setAvatar(url: string | null): void {
    if (user.value === null) {
      return;
    }
    user.value = { ...user.value, avatar: url };
    tokenStorage().setItem(USER_KEY, JSON.stringify(user.value));
  }

  /** 换发令牌（修改密码后）：写回令牌当前所在的那一档存储，不改变「记住我」的选择 */
  function replaceToken(value: string): void {
    token.value = value;
    tokenStorage().setItem(TOKEN_KEY, value);
  }

  function clearToken(): void {
    token.value = null;
    user.value = null;
    clearStored();
  }

  return {
    token,
    isLoggedIn,
    expiresAt,
    rememberTier,
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
    replaceToken,
    clearToken,
  };
});

/** 解析 JWT 载荷；仅用于取过期时刻，用户信息一律走登录响应下发的快照 */
function decodePayload(value: string | null): Record<string, unknown> | null {
  const payload = value?.split('.')[1];
  if (!payload) {
    return null;
  }
  try {
    const normalized = payload.replace(/-/g, '+').replace(/_/g, '/');
    const padded = normalized.padEnd(Math.ceil(normalized.length / 4) * 4, '=');
    const bytes = Uint8Array.from(atob(padded), (char) => char.charCodeAt(0));
    const parsed: unknown = JSON.parse(new TextDecoder().decode(bytes));
    return parsed && typeof parsed === 'object' ? (parsed as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}

/** 解析 JWT 载荷中的数字声明（exp）；缺失或非数字时返回 null */
function readNumberClaim(value: string | null, key: string): number | null {
  const claim = decodePayload(value)?.[key];
  return typeof claim === 'number' && Number.isFinite(claim) ? claim : null;
}
