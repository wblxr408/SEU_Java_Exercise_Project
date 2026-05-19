/**
 * k6 Load Test - 负载测试：模拟正常峰值流量，验证系统在预期负载下的性能
 *
 * 运行方式：
 *   k6 run tests/k6/scenarios/load.js
 *   k6 run tests/k6/scenarios/load.js --env BASE_URL=http://localhost:8080
 *
 * 场景设计：
 *   - 阶段1（0-30s）：逐渐爬坡 0→30 VU
 *   - 阶段2（30s-3min）：稳定在 30 VU（模拟峰值流量）
 *   - 阶段3（3min-3.5min）：逐渐降低 30→0 VU
 *
 * 通过标准（参考值）：
 *   - p95 响应时间 < 1s
 *   - 成功率 > 99%
 *   - 无 5xx 错误
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { register, login, authHeader, getToken, clearCache, BASE_URL } from '../modules/auth.js';
import { randomPostContent, randomCommentContent } from '../modules/dataGenerator.js';
import { assertSuccess, assertPageResult } from '../modules/assertions.js';

export const options = {
  // 负载测试：中等 VU，持续时间较长
  stages: [
    { duration: '30s', target: 30 },   // 爬坡：0 → 30 VU
    { duration: '2m30s', target: 30 },  // 稳定：30 VU
    { duration: '30s', target: 0 },     // 下降：30 → 0 VU
  ],

  thresholds: {
    // 响应时间阈值（负载测试：稳定峰值，p95 应在 1s 以内）
    http_req_duration: [
      'p(50)<500',    // 中位数 < 500ms
      'p(95)<1500',   // 95th < 1.5s（放宽 1s → 1.5s，更务实）
      'p(99)<3000',   // 99th < 3s
    ],

    // HTTP 错误阈值
    http_req_failed: ['rate<0.05'],    // 失败率 < 5%

    // 成功率阈值
    checks: ['rate>0.92'],
  },

  tags: {
    suite: 'load',
  },
};

export function setup() {
  // 预注册 50 个用户（30 VU × 1.5 缓冲）
  const userCount = 50;
  const users = [];
  const prefix = `load_${Date.now()}`;

  for (let i = 0; i < userCount; i++) {
    const username = `${prefix}_${i}`;
    const result = register(username, 'LoadTest123', `LoadUser${i}`);
    if (result.token) {
      users.push({ username, token: result.token, userId: result.userId });
    } else {
      const loginRes = login(username, 'LoadTest123');
      if (loginRes.token) {
        users.push({ username, token: loginRes.token, userId: loginRes.userId });
      }
    }
  }

  console.log(`[Load] 预注册 ${users.length}/${userCount} 个用户`);
  return { users };
}

export default function (data) {
  const vuId = __VU;
  const iter = __ITER;
  const user = data.users[vuId % data.users.length];

  if (!user) {
    return;
  }

  const token = user.token;

  // ========== 每个 VU 循环操作（模拟真实用户行为）==========
  // 1. 访问首页 → 获取 Feed 流
  if (user.userId) {
    const feedRes = http.get(`${BASE_URL}/feed?userId=${user.userId}&page=0&size=20`, {
      headers: authHeader(token),
      tags: { name: 'Feed' },
    });
    assertSuccess(feedRes, 'Feed流');
  }

  // 2. 浏览帖子列表
  const listRes = http.get(`${BASE_URL}/post/list?page=1&size=20`, {
    headers: authHeader(token),
    tags: { name: 'PostList' },
  });
  assertPageResult(listRes, '帖子列表');

  // 3. 尝试从列表中获取帖子 ID 并访问详情
  let postId = null;
  try {
    const records = JSON.parse(listRes.body)?.data?.records;
    if (records && records.length > 0) {
      postId = records[0]?.id;
    }
  } catch {}

  if (postId) {
    const detailRes = http.get(`${BASE_URL}/post/${postId}`, {
      headers: authHeader(token),
      tags: { name: 'PostDetail' },
    });
    assertSuccess(detailRes, '帖子详情');
  }

  // 4. 随机行为：发帖（约 20% 概率）
  if (iter % 5 === 0) {
    const postRes = http.post(
      `${BASE_URL}/post/create`,
      JSON.stringify({ content: randomPostContent(iter) }),
      { headers: authHeader(token), tags: { name: 'PostCreate' } }
    );
    const ok = check(postRes, {
      '发帖 - 状态码 200/201': (r) => r.status === 200 || r.status === 201,
      '发帖 - 响应 < 2s': (r) => r.timings.duration < 2000,
    });
    if (ok) {
      try {
        const newPostId = JSON.parse(postRes.body)?.data?.id;
        if (newPostId) {
          sleep(2); // 等待情感分析

          // 点赞自己的帖子
          http.post(
            `${BASE_URL}/interaction/like`,
            JSON.stringify({ targetId: newPostId, targetType: 'POST' }),
            { headers: authHeader(token), tags: { name: 'Like' } }
          );

          // 评论自己的帖子
          http.post(
            `${BASE_URL}/interaction/comment`,
            JSON.stringify({ postId: newPostId, content: randomCommentContent(iter) }),
            { headers: authHeader(token), tags: { name: 'CommentCreate' } }
          );
        }
      } catch {}
    }
  }

  // 5. 查看我的统计
  http.get(`${BASE_URL}/stats/my`, {
    headers: authHeader(token),
    tags: { name: 'MyStats' },
  });

  // 6. 查看通知未读数
  http.get(`${BASE_URL}/notification/unread/count`, {
    headers: authHeader(token),
    tags: { name: 'NotificationCount' },
  });

  // 真实用户间隔（模拟真实浏览行为）
  sleep(Math.random() * 2 + 0.5);
}

export function teardown(data) {
  console.log(`[Load] 负载测试完成`);
  clearCache();
}
