/**
 * k6 Stress Test - 压力测试：逐步加压，发现系统的性能瓶颈和断点
 *
 * 运行方式：
 *   k6 run tests/k6/scenarios/stress.js
 *   k6 run tests/k6/scenarios/stress.js --env BASE_URL=http://localhost:8080
 *
 * 场景设计（渐进式加压）：
 *   - 阶段1（1min）：10 VU  预热
 *   - 阶段2（2min）：30 VU  正常
 *   - 阶段3（2min）：50 VU  略超预期
 *   - 阶段4（2min）：80 VU  高负载
 *   - 阶段5（2min）：120 VU 极限
 *   - 阶段6（1min）：0 VU   冷却
 *
 * 关注指标：
 *   - 各阶段的 p95/p99 响应时间变化趋势
 *   - 5xx 错误开始出现的 VU 数量
 *   - 吞吐量（requests/s）是否达到上限
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { register, login, authHeader, getToken, clearCache, BASE_URL } from '../modules/auth.js';
import { randomPostContent } from '../modules/dataGenerator.js';
import { assertSuccess, assertPageResult } from '../modules/assertions.js';

export const options = {
  vus: 120,    // 最大虚拟用户数
  duration: '10m',

  stages: [
    { duration: '1m',  target: 10  },   // 预热
    { duration: '2m',  target: 30  },   // 正常
    { duration: '2m',  target: 50  },   // 略超预期
    { duration: '2m',  target: 80  },   // 高负载
    { duration: '2m',  target: 120 },   // 极限
    { duration: '1m',  target: 0   },   // 冷却
  ],

  thresholds: {
    // 压力测试关注 p99 和 p(95) 的变化趋势，不设硬性通过线
    http_req_duration: [
      'p(50)<800',
      'p(95)<2000',
      'p(99)<5000',
    ],

    http_req_failed: ['rate<0.05'],  // 允许少量 5xx（记录并分析）
    checks: ['rate>0.90'],
  },

  tags: {
    suite: 'stress',
  },
};

export function setup() {
  const userCount = 150;
  const users = [];
  const prefix = `stress_${Date.now()}`;

  for (let i = 0; i < userCount; i++) {
    const username = `${prefix}_${i}`;
    const result = register(username, 'StressTest123', `StressUser${i}`);
    if (result.token) {
      users.push({ username, token: result.token, userId: result.userId });
    } else {
      const loginRes = login(username, 'StressTest123');
      if (loginRes.token) {
        users.push({ username, token: loginRes.token, userId: loginRes.userId });
      }
    }
  }

  console.log(`[Stress] 预注册 ${users.length}/${userCount} 个用户`);
  return { users };
}

export default function (data) {
  const vuId = __VU;
  const iter = __ITER;
  const user = data.users[vuId % data.users.length];

  if (!user) return;

  const token = user.token;

  // ========== 操作序列（轻量级，最小化think time）==========
  const actions = [
    // 0: Feed 流
    () => {
      if (!user.userId) return null;
      const res = http.get(`${BASE_URL}/feed?userId=${user.userId}&page=0&size=20`, {
        headers: authHeader(token),
        tags: { name: 'Feed' },
      });
      assertSuccess(res, 'Feed');
      return res;
    },

    // 1: 帖子列表
    () => {
      const res = http.get(`${BASE_URL}/post/list?page=1&size=20`, {
        headers: authHeader(token),
        tags: { name: 'PostList' },
      });
      assertPageResult(res, 'PostList');
      return res;
    },

    // 2: 统计接口
    () => {
      const res = http.get(`${BASE_URL}/stats/my`, {
        headers: authHeader(token),
        tags: { name: 'MyStats' },
      });
      assertSuccess(res, 'MyStats');
      return res;
    },

    // 3: 发帖（低频，约 10% 概率）
    () => {
      if (iter % 10 !== 0) return null;
      const res = http.post(
        `${BASE_URL}/post/create`,
        JSON.stringify({ content: randomPostContent(iter) }),
        { headers: authHeader(token), tags: { name: 'PostCreate' } }
      );
      check(res, {
        '发帖 - 状态 2xx': (r) => r.status >= 200 && r.status < 300,
      });
      return res;
    },

    // 4: 通知未读数
    () => {
      const res = http.get(`${BASE_URL}/notification/unread/count`, {
        headers: authHeader(token),
        tags: { name: 'NotifCount' },
      });
      check(res, { '通知 - 状态 200': (r) => r.status === 200 });
      return res;
    },
  ];

  // 每个 VU 每轮执行 1-3 个操作
  const count = 1 + (iter % 3);
  for (let i = 0; i < count; i++) {
    const actionIndex = (iter + i) % actions.length;
    try {
      actions[actionIndex]();
    } catch (e) {
      console.error(`[Stress] VU ${vuId} action ${actionIndex} error: ${e.message}`);
    }
  }

  sleep(0.3); // 短间隔，最大化并发压力
}

export function teardown(data) {
  console.log('[Stress] 压力测试完成');
  clearCache();
}
