export const ACCESS_TOKEN_KEY = 'ai_interview_access_token';
export const REFRESH_TOKEN_KEY = 'ai_interview_refresh_token';
export const USER_KEY = 'ai_interview_user';
export const AUTH_CHANGED_EVENT = 'ai-interview-auth-changed';

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
