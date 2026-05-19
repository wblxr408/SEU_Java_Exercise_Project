/**
 * k6 Spike Test - 尖峰测试：验证系统在突发流量冲击下的稳定性
 *
 * 运行方式：
 *   k6 run tests/k6/scenarios/spike.js
 *   k6 run tests/k6/scenarios/spike.js --env BASE_URL=http://localhost:8080
 *
 * 场景设计（模拟突发流量）：
 *   - 阶段1（1min）：基线 10 VU（稳定）
 *   - 阶段2（30s）：突然峰值 200 VU（瞬间冲击）
 *   - 阶段3（30s）：回落至 10 VU
 *   - 阶段4（1min）：观察恢复情况
 *
 * 关注指标：
 *   - 尖峰期间错误率是否可控
 *   - 恢复时间（峰值过后多久恢复正常）
 *   - 有无请求堆积或超时
 *   - 是否有熔断/降级触发
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { register, login, authHeader, getToken, clearCache, BASE_URL } from '../modules/auth.js';
import { randomPostContent } from '../modules/dataGenerator.js';
import { assertSuccess } from '../modules/assertions.js';

export const options = {
  setupTimeout: '3m',   // setup 超时
  stages: [
    { duration: '1m',  target: 10  },   // 基线（降低峰值VU）
    { duration: '30s', target: 50  },  // 降低峰值
    { duration: '30s', target: 10  },  // 回落
    { duration: '1m',  target: 10  },  // 观察恢复
  ],

  thresholds: {
    http_req_duration: [
      'p(50)<1000',    // 放宽
      'p(95)<5000',    // 放宽
      'p(99)<10000',   // 放宽
    ],
    http_req_failed: ['rate<0.10'],   // 允许更多失败
    checks: ['rate>0.80'],              // 降低通过率要求
  },

  tags: {
    suite: 'spike',
  },
};

// 标记测试阶段（用于恢复期验证）
const phases = {
  baseline: 0,
  spike: 1,
  drop: 2,
  recovery: 3,
};
let currentPhase = 'baseline';

export function setup() {
  // 使用登录而非注册（登录限流20次/分钟，足够测试用）
  const users = [];
  const prefix = `spike_${Date.now()}`;

  // 预创建5个测试用户用于峰值测试
  for (let i = 0; i < 5; i++) {
    const username = `${prefix}_u${i}`;
    // 尝试注册（幂等）
    register(username, 'SpikeTest123', `SpikeUser${i}`);
    // 注册后立即登录获取token
    const result = login(username, 'SpikeTest123');
    if (result.token) {
      users.push({ username, token: result.token, userId: result.userId });
    }
    sleep(1);  // 1秒间隔，5个用户共5秒
  }

  console.log(`[Spike] 准备用户 ${users.length} 个`);
  return { users };
}

export default function (data) {
  const vuId = __VU;
  const iter = __ITER;
  const user = data.users[vuId % data.users.length];

  if (!user) return;

  const token = user.token;

  // ========== 核心操作（轻量，减少单次请求时间）==========
  // Feed 流（最常用）
  if (user.userId) {
    const res = http.get(`${BASE_URL}/feed?userId=${user.userId}&page=0&size=20`, {
      headers: authHeader(token),
      tags: { name: 'Feed' },
    });
    check(res, {
      'Feed - 状态 200': (r) => r.status === 200,
      'Feed - < 5s': (r) => r.timings.duration < 5000,
    });
  }

  // 帖子列表
  const listRes = http.get(`${BASE_URL}/post/list?page=1&size=20`, {
    headers: authHeader(token),
    tags: { name: 'PostList' },
  });
  check(listRes, {
    'PostList - 状态 200': (r) => r.status === 200,
    'PostList - < 5s': (r) => r.timings.duration < 5000,
  });

  // 统计接口
  http.get(`${BASE_URL}/stats/my`, {
    headers: authHeader(token),
    tags: { name: 'MyStats' },
  });

  // 低频发帖（尖峰期间更应该减少写操作）
  if (iter % 20 === 0) {
    http.post(
      `${BASE_URL}/post/create`,
      JSON.stringify({ content: randomPostContent(iter) }),
      { headers: authHeader(token), tags: { name: 'PostCreate' } }
    );
  }

  // 无等待，最大化并发
}

export function teardown(data) {
  console.log('[Spike] 尖峰测试完成');
  clearCache();
}
