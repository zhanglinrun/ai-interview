import { useSyncExternalStore } from 'react';
import {
  AUTH_CHANGED_EVENT,
  getStoredUser,
  USER_KEY,
  type StoredUser,
} from '../api/authStorage';

let cachedRawUser: string | null | undefined;
let cachedUser: StoredUser | null = null;

function subscribe(onStoreChange: () => void): () => void {
  window.addEventListener(AUTH_CHANGED_EVENT, onStoreChange);
  window.addEventListener('storage', onStoreChange);
  return () => {
    window.removeEventListener(AUTH_CHANGED_EVENT, onStoreChange);
    window.removeEventListener('storage', onStoreChange);
  };
}

function snapshot(): StoredUser | null {
  // useSyncExternalStore 要求 snapshot 在状态未变化时返回同一个引用。
  // getStoredUser() 每次解析 localStorage 都会创建新对象，登录后会触发
  // React 的无限重渲染；按原始存储值缓存解析结果即可保持引用稳定。
  const rawUser = typeof window !== 'undefined' && window.localStorage
    ? window.localStorage.getItem(USER_KEY)
    : null;
  if (rawUser === cachedRawUser) {
    return cachedUser;
  }
  cachedRawUser = rawUser;
  cachedUser = getStoredUser();
  return cachedUser;
}

/** 登录态的唯一前端订阅入口，避免 Layout/页面各自维护一份用户状态。 */
export function useAuthStore(): StoredUser | null {
  return useSyncExternalStore(subscribe, snapshot, () => null);
}
