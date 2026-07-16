export const DEFAULT_AUTH_RETURN_TO = '/dashboard';

/**
 * 只接受当前站点内的绝对路径，避免登录回跳参数形成 open redirect。
 * URL 会在这里规范化，但登录态和权限始终由后端判断。
 */
export function resolveSafeReturnTo(
  candidate: string | null | undefined,
  fallback = DEFAULT_AUTH_RETURN_TO,
): string {
  if (!candidate?.startsWith('/')) {
    return fallback;
  }

  try {
    const baseOrigin = typeof window !== 'undefined'
      ? window.location.origin
      : 'http://localhost';
    const target = new URL(candidate, baseOrigin);
    if (target.origin !== baseOrigin || target.pathname === '/login') {
      return fallback;
    }
    return `${target.pathname}${target.search}${target.hash}`;
  } catch {
    return fallback;
  }
}
