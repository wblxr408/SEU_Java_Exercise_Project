import { check, sleep } from 'k6';
import http from 'k6/http';

/**
 * 认证模块 - 提供登录/注册/Token 管理
 *
 * 使用方式：
 *   import { auth } from '../modules/auth.js';
 *   const { token, userId } = auth.login(username, password);
 */

// 全局 Token 池（避免每个 VU 重复登录）
const tokenCache = {};

// 测试数据基准前缀（避免多进程/多机器冲突）
const RUN_ID = __ENV.K6_RUN_ID || Math.random().toString(36).substring(2, 10);

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081/api';

/**
 * 生成唯一用户名（防止并发注册冲突）
 * @param {number} vuId   - 虚拟用户 ID
 * @param {number} iter   - 当前迭代编号
 */
export function uniqueUsername(vuId, iter = 0) {
  return `k6_${RUN_ID}_vu${vuId}_${iter}_${Date.now()}`;
}

/**
 * 发送登录请求
 * @param {string} username
 * @param {string} password
 * @returns {{ token: string, userId: number|null, response: Response }}
 */
export function login(username, password) {
  const payload = JSON.stringify({ username, password });
  const params = {
    headers: { 'Content-Type': 'application/json' },
    tags: { name: 'AuthLogin' },
  };

  const res = http.post(`${BASE_URL}/auth/login`, payload, params);

  const ok = check(res, {
    '登录成功 (200)': (r) => r.status === 200,
    '响应包含 token': (r) => {
      try {
        const body = JSON.parse(r.body);
        return !!body.data?.token;
      } catch {
        return false;
      }
    },
  });

  let token = null;
  let userId = null;
  if (ok && res.status === 200) {
    try {
      const body = JSON.parse(res.body);
      token = body.data?.token;
      userId = body.data?.userInfo?.id ?? null;
    } catch {}
  }

  return { token, userId, response: res, ok };
}

/**
 * 发送注册请求
 * @param {string} username
 * @param {string} password
 * @param {string} [nickname]
 * @returns {{ token: string, userId: number|null, response: Response }}
 */
export function register(username, password, nickname) {
  const payload = JSON.stringify({
    username,
    password,
    email: `${username}@k6.local`,
    nickname: nickname || username,
  });
  const params = {
    headers: { 'Content-Type': 'application/json' },
    tags: { name: 'AuthRegister' },
  };

  const res = http.post(`${BASE_URL}/auth/register`, payload, params);

  const ok = check(res, {
    '注册成功 (200)': (r) => r.status === 200,
    '响应包含 token': (r) => {
      try {
        const body = JSON.parse(r.body);
        return !!body.data?.token;
      } catch {
        return false;
      }
    },
  });

  let token = null;
  let userId = null;
  if (ok && res.status === 200) {
    try {
      const body = JSON.parse(res.body);
      token = body.data?.token;
      userId = body.data?.userInfo?.id ?? null;
    } catch {}
  }

  return { token, userId, response: res, ok };
}

/**
 * 获取 Token（优先从缓存返回，若无则自动注册+登录）
 * @param {number} vuId - 虚拟用户 ID（用于生成唯一用户名）
 * @param {number} iter - 迭代编号（用于生成唯一用户名）
 * @returns {{ token: string, userId: number }}
 */
export function getToken(vuId, iter = 0) {
  const key = `${vuId}_${iter}`;
  if (tokenCache[key]) {
    return tokenCache[key];
  }

  const username = uniqueUsername(vuId, iter);
  const password = 'k6TestPass123';

  // 优先尝试注册（幂等）
  let result = register(username, password, `K6User_${vuId}_${iter}`);
  if (!result.ok) {
    // 注册失败（如用户名已存在），改用登录
    result = login(username, password);
  }

  if (!result.token) {
    throw new Error(`无法获取 Token（VU=${vuId}, iter=${iter}）: ${result.response?.body}`);
  }

  tokenCache[key] = { token: result.token, userId: result.userId };
  return tokenCache[key];
}

/**
 * 获取已登录用户的 Authorization header
 */
export function authHeader(token) {
  return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };
}

/**
 * 获取当前登录用户信息
 */
export function getCurrentUser(token) {
  const params = {
    headers: authHeader(token),
    tags: { name: 'AuthCurrent' },
  };
  return http.get(`${BASE_URL}/auth/current`, params);
}

/**
 * 清理缓存（每个场景结束后可调用）
 */
export function clearCache() {
  Object.keys(tokenCache).forEach((k) => delete tokenCache[k]);
}

export { BASE_URL };
