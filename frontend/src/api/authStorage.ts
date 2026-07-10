export const ACCESS_TOKEN_KEY = 'ai_interview_access_token';
export const REFRESH_TOKEN_KEY = 'ai_interview_refresh_token';
export const USER_KEY = 'ai_interview_user';
export const AUTH_CHANGED_EVENT = 'ai-interview-auth-changed';

export type UserRole = 'ADMIN' | 'USER';

export interface StoredUser {
  userId: number;
  username: string;
  displayName?: string;
}

export interface AuthSession {
  accessToken: string;
  refreshToken: string;
  userId: number;
  username: string;
  displayName?: string;
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

/** 解析 JWT payload（不校验签名，仅用于前端读取 claim；鉴权仍以后端为准）。 */
function decodeJwtPayload(token: string): Record<string, unknown> | null {
  const part = token.split('.')[1];
  if (!part) return null;
  try {
    const base64 = part.replace(/-/g, '+').replace(/_/g, '/');
    const padded = base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), '=');
    const binary = atob(padded);
    const bytes = Uint8Array.from(binary, ch => ch.charCodeAt(0));
    return JSON.parse(new TextDecoder().decode(bytes)) as Record<string, unknown>;
  } catch {
    return null;
  }
}

/** 当前登录用户角色（从 access token 的 role claim 解析，无法解析时返回 null）。 */
export function getUserRole(): UserRole | null {
  const token = getAccessToken();
  if (!token) return null;
  const role = decodeJwtPayload(token)?.role;
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
  window.localStorage.setItem(REFRESH_TOKEN_KEY, session.refreshToken);
  window.localStorage.setItem(USER_KEY, JSON.stringify({
    userId: session.userId,
    username: session.username,
    displayName: session.displayName,
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
