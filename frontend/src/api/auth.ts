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
    const session = await request.post<AuthSession>('/api/auth/login', data);
    setAuthSession(session);
    return session;
  },

  async register(data: RegisterRequest): Promise<AuthSession> {
    const session = await request.post<AuthSession>('/api/auth/register', data);
    setAuthSession(session);
    return session;
  },

  logout() {
    clearAuthSession();
  },
};
