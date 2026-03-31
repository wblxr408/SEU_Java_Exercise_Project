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
  vus: 200,   // 峰值时 200 VU
  duration: '4m',

  stages: [
    { duration: '1m',  target: 10  },   // 基线
    { duration: '30s', target: 200 },  // 瞬间峰值
    { duration: '30s', target: 10  },  // 快速回落
    { duration: '2m',  target: 10  },   // 观察恢复
  ],

  thresholds: {
    // 尖峰期间允许响应时间上升，但恢复后应回到正常水平
    http_req_duration: [
      'p(50)<800',    // 中位数 < 800ms
      'p(95)<3000',   // 峰值期间允许 p95 上升到 3s
      'p(99)<6000',   // 峰值期间允许 p99 上升到 6s
    ],

    // 峰值后（2min 内）应恢复正常
    'http_req_duration{phase:recovery}': ['p(95)<1500'],

    http_req_failed: ['rate<0.05'],  // 允许峰值期间少量失败
    checks: ['rate>0.90'],
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
  const userCount = 250;
  const users = [];
  const prefix = `spike_${Date.now()}`;

  for (let i = 0; i < userCount; i++) {
    const username = `${prefix}_${i}`;
    const result = register(username, 'SpikeTest123', `SpikeUser${i}`);
    if (result.token) {
      users.push({ username, token: result.token, userId: result.userId });
    } else {
      const loginRes = login(username, 'SpikeTest123');
      if (loginRes.token) {
        users.push({ username, token: loginRes.token, userId: loginRes.userId });
      }
    }
  }

  console.log(`[Spike] 预注册 ${users.length}/${userCount} 个用户`);
  return { users, phaseStart: Date.now() };
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
