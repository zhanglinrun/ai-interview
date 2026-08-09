import { request } from './request';
import { AuthSession, clearAuthSession, setAuthSession } from './authStorage';

export interface LoginRequest {
  username: string;
  password: string;
}

export interface RegisterRequest {
  username: string;
  email: string;
  password: string;
  displayName?: string;
}

export const authApi = {
  async login(data: LoginRequest): Promise<AuthSession> {
    const session = await request.post<AuthSession>('/api/v1/auth/login', data);
    setAuthSession(session);
    return session;
  },

  async register(data: RegisterRequest): Promise<AuthSession> {
    const session = await request.post<AuthSession>('/api/v1/auth/register', data);
    setAuthSession(session);
    return session;
  },

  async logout() {
    try {
      await request.post<void>('/api/v1/auth/logout');
    } finally {
      clearAuthSession();
    }
  },

  async me(): Promise<{ id: number; username: string; email: string; displayName: string; role: string }> {
    return request.get('/api/v1/auth/me');
  },

  clearLocalSession() {
    clearAuthSession();
  },
};
