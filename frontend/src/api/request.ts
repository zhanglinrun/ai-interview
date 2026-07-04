import axios, { AxiosRequestConfig, InternalAxiosRequestConfig } from 'axios';
import {
  AuthSession,
  clearAuthSession,
  getAccessToken,
  getRefreshToken,
  setAuthSession,
} from './authStorage';

/**
 * 后端统一响应结构
 */
interface Result<T = unknown> {
  code: number;
  message: string;
  data: T;
}

export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '';
export const DEFAULT_REQUEST_TIMEOUT_MS = 60_000;
export const AI_REQUEST_TIMEOUT_MS = 180_000;
export const UPLOAD_REQUEST_TIMEOUT_MS = 300_000;

const instance = axios.create({
  baseURL: API_BASE_URL,
  timeout: DEFAULT_REQUEST_TIMEOUT_MS,
});

export function getAuthHeaders(): Record<string, string> {
  const token = getAccessToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

instance.interceptors.request.use((config) => {
  const authHeaders = getAuthHeaders();
  if (authHeaders.Authorization) {
    config.headers = config.headers ?? {};
    config.headers.Authorization = authHeaders.Authorization;
  }
  return config;
});

function handleUnauthorized(code?: number, message?: string) {
  if (code !== 401 && !message?.includes('token')) {
    return;
  }
  clearAuthSession();
  if (typeof window !== 'undefined' && window.location.pathname !== '/login') {
    const from = `${window.location.pathname}${window.location.search}`;
    window.location.href = `/login?from=${encodeURIComponent(from)}`;
  }
}

interface RetriableConfig extends InternalAxiosRequestConfig {
  /** 标记该请求已用刷新后的 token 重放过，避免无限循环 */
  _authRetried?: boolean;
}

let refreshPromise: Promise<AuthSession> | null = null;

/** 用 refresh token 换新 access token；并发 401 共享同一次刷新请求 */
function refreshAuthSession(): Promise<AuthSession> {
  if (!refreshPromise) {
    const refreshToken = getRefreshToken();
    if (!refreshToken) {
      return Promise.reject(new Error('无 refresh token'));
    }
    // 用裸 axios 避开实例拦截器（防递归刷新）
    refreshPromise = axios
      .post<Result<AuthSession>>(`${API_BASE_URL}/api/auth/refresh`, null, {
        headers: { 'Refresh-Token': refreshToken },
        timeout: DEFAULT_REQUEST_TIMEOUT_MS,
      })
      .then((res) => {
        const result = res.data;
        if (!isResult(result) || result.code !== 200) {
          throw new Error(isResult(result) ? result.message : '刷新登录态失败');
        }
        const session = result.data as AuthSession;
        setAuthSession(session);
        return session;
      })
      .finally(() => {
        refreshPromise = null;
      });
  }
  return refreshPromise;
}

function canSilentRefresh(result: Result, config?: RetriableConfig): boolean {
  return Boolean(
    config
    && !config._authRetried
    && !config.url?.includes('/api/auth/')
    && (result.code === 401 || result.message?.includes('token'))
    && getRefreshToken(),
  );
}

function isResult(value: unknown): value is Result {
  if (!value || typeof value !== 'object') {
    return false;
  }
  const candidate = value as Partial<Result>;
  return typeof candidate.code === 'number' && typeof candidate.message === 'string';
}

function rejectResult(result: Result) {
  handleUnauthorized(result.code, result.message);
  return Promise.reject(new Error(result.message || '请求失败'));
}

async function parseBlobResult(blob: Blob): Promise<Blob> {
  if (!blob.type.includes('application/json')) {
    return blob;
  }

  let data: unknown;
  try {
    data = JSON.parse(await blob.text()) as unknown;
  } catch {
    return blob;
  }

  if (isResult(data) && data.code !== 200) {
    return rejectResult(data);
  }

  return blob;
}

/**
 * 响应拦截器
 * 
 * 后端约定：所有响应都是 HTTP 200 + Result
 * - code === 200 → 成功，返回 data
 * - code !== 200 → 失败，直接显示 message
 */
instance.interceptors.response.use(
  async (response) => {
    // 检查是否是 Result 格式
    if (isResult(response.data)) {
      const result = response.data;
      if (result.code === 200) {
        // 成功：返回 data
        response.data = result.data;
        return response;
      }
      // 401：先尝试静默刷新 token 并重放一次，失败才跳登录页
      const config = response.config as RetriableConfig;
      if (canSilentRefresh(result, config)) {
        let session: AuthSession;
        try {
          session = await refreshAuthSession();
        } catch {
          return rejectResult(result);
        }
        config._authRetried = true;
        config.headers.Authorization = `Bearer ${session.accessToken}`;
        return instance.request(config);
      }
      // 失败：直接抛出 message
      return rejectResult(result);
    }
    
    // 非 Result 格式，直接返回
    return response;
  },
  (error) => {
    // 有响应的情况：后端返回了结果（即使是错误）
    if (error.response) {
      const { data } = error.response;
      // 尝试解析 Result 格式
      if (isResult(data)) {
        return rejectResult(data);
      }
      // 响应格式不对
      return Promise.reject(new Error('请求失败，请重试'));
    }

    // 没有响应的情况：真正的网络错误或连接被重置
    // 对于文件上传，可能是网络超时或连接中断，但不一定是文件大小问题
    // 让后端返回真实的错误信息，而不是在这里假设
    const config = error.config;
    const isUpload = config && (
      config.url?.includes('/upload') ||
      config.headers?.['Content-Type']?.toString().includes('multipart')
    );

    if (isUpload) {
      // 文件上传失败且没有响应，可能是网络超时或连接中断
      // 不直接假设是文件大小问题，返回更通用的错误信息
      return Promise.reject(new Error('上传失败，可能是网络超时或连接中断，请重试'));
    }

    // 其他网络错误
    return Promise.reject(new Error('网络连接失败，请检查网络'));
  }
);

export const request = {
  get<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
    return instance.get(url, config).then(res => res.data);
  },

  getBlob(url: string, config?: AxiosRequestConfig): Promise<Blob> {
    return instance.get(url, {
      ...config,
      responseType: 'blob',
    }).then(res => parseBlobResult(res.data));
  },

  post<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return instance.post(url, data, config).then(res => res.data);
  },

  put<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return instance.put(url, data, config).then(res => res.data);
  },

  patch<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return instance.patch(url, data, config).then(res => res.data);
  },

  delete<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
    return instance.delete(url, config).then(res => res.data);
  },

  /**
   * 文件上传
   */
  upload<T>(url: string, formData: FormData, config?: AxiosRequestConfig): Promise<T> {
    return instance.post(url, formData, {
      timeout: UPLOAD_REQUEST_TIMEOUT_MS, // 5分钟，与Nginx proxy_read_timeout对齐
      headers: { 'Content-Type': 'multipart/form-data' },
      ...config,
    }).then(res => res.data);
  },

};

/**
 * 获取错误信息
 */
export function getErrorMessage(error: unknown, fallback = '未知错误'): string {
  if (error instanceof Error && error.message) {
    return error.message;
  }
  return fallback;
}

export default request;

