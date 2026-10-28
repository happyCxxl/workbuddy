import axios from 'axios';
import { ElMessage } from 'element-plus';

import type { R } from '@/types/response';

const TOKEN_KEY = 'knowledge-token';

const CODE_UNAUTHORIZED = 40101;
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
});

http.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY) ?? sessionStorage.getItem(TOKEN_KEY);
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

/** 清令牌并携来源路径回登录页；已在登录页时不重复跳，避免刷新循环 */
function redirectToLogin(): void {
  localStorage.removeItem(TOKEN_KEY);
  sessionStorage.removeItem(TOKEN_KEY);
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
      if (body.code === CODE_UNAUTHORIZED) {
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
    // 令牌缺失/失效/被吊销时后端若直接返回 401/403，这里兜底回登录页
    if (status === HTTP_UNAUTHORIZED || status === HTTP_FORBIDDEN) {
      redirectToLogin();
      return Promise.reject(error);
    }
    // 原样响应的接口自己给失败提示（例如"取文件失败（HTTP 404）"），这里不插话
    if (error?.config?.rawResponse !== true) {
      ElMessage.error('网络异常，请检查服务是否可用');
    }
    return Promise.reject(error);
  },
);
