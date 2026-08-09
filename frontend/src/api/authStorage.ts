export const ACCESS_TOKEN_KEY = 'ai_interview_access_token';
export const REFRESH_TOKEN_KEY = 'ai_interview_refresh_token';
export const USER_KEY = 'ai_interview_user';
export const AUTH_CHANGED_EVENT = 'ai-interview-auth-changed';

export type UserRole = 'ADMIN' | 'USER';

export interface StoredUser {
  userId: number;
  username: string;
  displayName?: string;
  role?: UserRole;
}

export interface AuthSession {
  accessToken: string;
  refreshToken?: string | null;
  userId: number;
  username: string;
  displayName?: string;
  role?: UserRole;
}

function hasStorage() {
  return typeof window !== 'undefined' && Boolean(window.localStorage);
}

function isStoredUser(value: unknown): value is StoredUser {
  if (!value || typeof value !== 'object') {
    return false;
  }
  const user = value as Partial<StoredUser>;
  return typeof user.userId === 'number' && typeof user.username === 'string';
}

export function getAccessToken(): string | null {
  if (!hasStorage()) return null;
  return window.localStorage.getItem(ACCESS_TOKEN_KEY);
}

export function getRefreshToken(): string | null {
  if (!hasStorage()) return null;
  return window.localStorage.getItem(REFRESH_TOKEN_KEY);
}

/** 当前登录用户角色。Sa-Token token 是不透明值，角色来自 /auth/me 或登录响应。 */
export function getUserRole(): UserRole | null {
  const role = getStoredUser()?.role;
  return role === 'ADMIN' || role === 'USER' ? role : null;
}

/** 是否为管理员。仅用于前端展示门控，后端接口仍以 requireAdmin 为准。 */
export function isAdmin(): boolean {
  return getUserRole() === 'ADMIN';
}

export function getStoredUser(): StoredUser | null {
  if (!hasStorage()) return null;
  const raw = window.localStorage.getItem(USER_KEY);
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw);
    return isStoredUser(parsed) ? parsed : null;
  } catch {
    return null;
  }
}

export function setAuthSession(session: AuthSession) {
  if (!hasStorage()) return;
  window.localStorage.setItem(ACCESS_TOKEN_KEY, session.accessToken);
  if (session.refreshToken) {
    window.localStorage.setItem(REFRESH_TOKEN_KEY, session.refreshToken);
  } else {
    window.localStorage.removeItem(REFRESH_TOKEN_KEY);
  }
  window.localStorage.setItem(USER_KEY, JSON.stringify({
    userId: session.userId,
    username: session.username,
    displayName: session.displayName,
    role: session.role,
  }));
  window.dispatchEvent(new Event(AUTH_CHANGED_EVENT));
}

export function clearAuthSession() {
  if (!hasStorage()) return;
  window.localStorage.removeItem(ACCESS_TOKEN_KEY);
  window.localStorage.removeItem(REFRESH_TOKEN_KEY);
  window.localStorage.removeItem(USER_KEY);
  window.dispatchEvent(new Event(AUTH_CHANGED_EVENT));
}
