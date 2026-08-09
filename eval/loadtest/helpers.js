// k6 压测公共辅助：统一 BASE_URL、自动登录换 token、鉴权头。
//
// 鉴权两种方式（二选一）：
//   1) 直接给 access token：  -e TOKEN=satoken...
//   2) 给账号密码自动登录：   -e AUTH_USER=admin -e AUTH_PASSWORD=xxx
// 在脚本的 setup() 里调用一次 resolveToken()，k6 会把返回值传给各 VU 的 default(data)。

import http from 'k6/http';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8082';

/** 解析逗号分隔的数字 id 列表。 */
export function parseIds(raw, fallback = '') {
  return String(raw ?? fallback)
    .split(',')
    .map((s) => parseInt(s.trim(), 10))
    .filter((n) => !Number.isNaN(n));
}

/**
 * 获取 access token：优先 -e TOKEN；否则用 -e AUTH_USER/-e AUTH_PASSWORD 登录 /api/v1/auth/login 换取。
 * 只应在 setup() 中调用一次，避免每个 VU 都登录。返回空串表示匿名（受保护接口会 401）。
 */
export function resolveToken() {
  if (__ENV.TOKEN) {
    return __ENV.TOKEN;
  }
  const username = __ENV.AUTH_USER;
  const password = __ENV.AUTH_PASSWORD;
  if (!username || !password) {
    console.warn('[helpers] 未提供 TOKEN，也未提供 AUTH_USER/AUTH_PASSWORD，将匿名请求（受保护接口会 401）');
    return '';
  }
  const res = http.post(
    `${BASE_URL}/api/v1/auth/login`,
    JSON.stringify({ username, password }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  try {
    const body = res.json();
    const token = body && body.data && body.data.accessToken;
    if (!token) {
      console.error(`[helpers] 登录失败（无 accessToken）: status=${res.status}, body=${res.body}`);
      return '';
    }
    console.log(`[helpers] 登录成功，已获取 token（user=${username}）`);
    return token;
  } catch (e) {
    console.error(`[helpers] 登录响应解析失败: status=${res.status}`);
    return '';
  }
}

/** 构造鉴权请求头。 */
export function authHeaders(token, extra = {}) {
  const headers = { 'Content-Type': 'application/json', ...extra };
  if (token) {
    headers.satoken = token;
  }
  return headers;
}
