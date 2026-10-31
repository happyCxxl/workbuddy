import axios from 'axios';
import { ElMessage } from 'element-plus';

import type { R } from '@/types/response';

const USER_KEY = 'knowledge-user';

const CODE_UNAUTHORIZED = 40101;
/** 灵犀（Python）域未认证码：与 Java 的 40101 同样处理 */
const CODE_AI_UNAUTHORIZED = 60003;
const CODE_SYSTEM_ERROR = 40500;

/** HTTP 401/403：兜底识别为未认证/无权限（后端正常情况下会把它们归一成 200 + R.code） */
const HTTP_UNAUTHORIZED = 401;
const HTTP_FORBIDDEN = 403;

declare module 'axios' {
  export interface AxiosRequestConfig {
    /**
     * 置 true 表示响应是原始数据（文件流等），不走统一响应体解包，也不弹统一错误提示。
     *
     * <p>文件流这类接口**直出字节**，没有 `R` 信封（`code`/`msg`/`data`）；
     * 照常解包会把 Blob 上的 `code` 读成 `undefined`，于是每次下载都被判成失败。
     */
    rawResponse?: boolean;
  }
}

// 统一请求实例：注入令牌；响应统一解包（非 0 码提示并拒绝，页面只处理成功分支）
export const http = axios.create({
  baseURL: '',
  timeout: 15000,
  // 令牌在 HttpOnly Cookie 里：跨源部署时也要带上凭证
  withCredentials: true,
});

const CSRF_HEADER = 'X-CSRF-Token';
const CSRF_COOKIE = 'XSRF-TOKEN';
const UNSAFE_METHODS = new Set(['post', 'put', 'patch', 'delete']);

function readCookie(name: string): string | null {
  const prefix = `${name}=`;
  for (const part of document.cookie.split(';')) {
    const item = part.trim();
    if (item.startsWith(prefix)) {
      return decodeURIComponent(item.slice(prefix.length));
    }
  }
  return null;
}

http.interceptors.request.use((config) => {
  const method = (config.method ?? 'get').toLowerCase();
  if (UNSAFE_METHODS.has(method)) {
    const csrf = readCookie(CSRF_COOKIE);
    if (csrf) {
      config.headers[CSRF_HEADER] = csrf;
    }
  }
  return config;
});

/** 清令牌并携来源路径回登录页；已在登录页时不重复跳，避免刷新循环 */
function redirectToLogin(): void {
  localStorage.removeItem(USER_KEY);
  sessionStorage.removeItem(USER_KEY);
  if (window.location.pathname === '/login') {
    return;
  }
  // 来源写进 redirect 参数：登录成功后由登录页读回
  const from = encodeURIComponent(window.location.pathname + window.location.search);
  window.location.href = `/login?redirect=${from}`;
}

http.interceptors.response.use(
  (response) => {
    if (response.config.rawResponse === true) {
      return response;
    }
    const body = response.data as R<unknown>;
    if (body.code !== 0) {
      if (body.code === CODE_UNAUTHORIZED || body.code === CODE_AI_UNAUTHORIZED) {
        redirectToLogin();
      } else if (body.code === CODE_SYSTEM_ERROR) {
        ElMessage.error('系统异常，请稍后重试');
      } else {
        ElMessage.error(body.msg || '操作失败');
      }
      return Promise.reject(new Error(body.msg || '操作失败'));
    }
    response.data = body.data;
    return response;
  },
  (error) => {
    const status = error?.response?.status;
    if (status === HTTP_UNAUTHORIZED) {
      redirectToLogin();
      return Promise.reject(error);
    }
    if (status === HTTP_FORBIDDEN) {
      // CSRF 校验失败：不跳登录，提示后让用户刷新重试
      ElMessage.error('请求校验失败，请刷新页面重试');
      return Promise.reject(error);
    }
    // 原样响应的接口自己给失败提示（例如"取文件失败（HTTP 404）"），这里不插话
    if (error?.config?.rawResponse !== true) {
      ElMessage.error('网络异常，请检查服务是否可用');
    }
    return Promise.reject(error);
  },
);
