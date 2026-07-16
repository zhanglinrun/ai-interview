import { useEffect, useState } from 'react';
import { Navigate, Outlet, useLocation } from 'react-router-dom';
import {
  ACCESS_TOKEN_KEY,
  AUTH_CHANGED_EVENT,
  getAccessToken,
} from '../api/authStorage';
import { resolveSafeReturnTo } from '../api/authNavigation';

function hasLocalAccessToken(): boolean {
  return Boolean(getAccessToken()?.trim());
}

/**
 * 路由级登录守卫只检查本地是否有 access token，不解析 JWT 来推断权限。
 * token 是否有效以及用户是否有权访问资源，仍由后端最终裁决。
 */
export default function RequireAuth() {
  const location = useLocation();
  const [authenticated, setAuthenticated] = useState(hasLocalAccessToken);

  useEffect(() => {
    const syncAuthentication = () => setAuthenticated(hasLocalAccessToken());
    const handleStorage = (event: StorageEvent) => {
      if (event.key === null || event.key === ACCESS_TOKEN_KEY) {
        syncAuthentication();
      }
    };

    window.addEventListener(AUTH_CHANGED_EVENT, syncAuthentication);
    window.addEventListener('storage', handleStorage);
    return () => {
      window.removeEventListener(AUTH_CHANGED_EVENT, syncAuthentication);
      window.removeEventListener('storage', handleStorage);
    };
  }, []);

  if (!authenticated) {
    const returnTo = resolveSafeReturnTo(
      `${location.pathname}${location.search}${location.hash}`,
    );
    const search = new URLSearchParams({ from: returnTo }).toString();
    return <Navigate to={{ pathname: '/login', search: `?${search}` }} replace />;
  }

  return <Outlet />;
}
