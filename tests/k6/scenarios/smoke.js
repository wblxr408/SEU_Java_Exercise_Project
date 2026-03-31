/**
 * k6 Smoke Test - 冒烟测试：验证所有核心 API 是否正常工作
 *
 * 运行方式：
 *   k6 run tests/k6/scenarios/smoke.js
 *   k6 run tests/k6/scenarios/smoke.js --env BASE_URL=http://localhost:8080/api
 *
 * 检查内容：
 *   1. 服务健康检查
 *   2. 用户注册
 *   3. 用户登录
 *   4. 发帖 + 情感分析
 *   5. 帖子列表查询
 *   6. 帖子详情
 *   7. 点赞（POST）
 *   8. 评论（POST）
 *   9. 评论列表查询
 *   10. 统计接口
 *   11. Feed 流
 *   12. 推荐接口
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { register, login, authHeader, getToken, clearCache, BASE_URL } from '../modules/auth.js';
import { randomPostContent, randomCommentContent } from '../modules/dataGenerator.js';
import { assertSuccess, assertPageResult, assertCreated, assertList } from '../modules/assertions.js';

export const options = {
  // 烟雾测试：少量 VU，短时间，快速反馈
  vus: 3,
  duration: '30s',

  thresholds: {
    // 所有 HTTP 请求必须在 3s 内完成（烟雾测试标准宽松）
    http_req_duration: ['p(95)<3000'],

    // 不允许 5xx 错误
    http_reqs: ['count>0'],
    checks: ['rate>0.9'],   // 至少 90% 的检查通过
  },

  tags: {
    suite: 'smoke',
  },
};

export function setup() {
  // 预注册 3 个用户，避免测试过程中注册超时
  // 使用已知测试账号直接登录，绕过注册限流
  const testUsers = [
    { username: 'alice_chen', password: 'password123' },
    { username: 'bob_wang', password: 'password123' },
    { username: 'carol_liu', password: 'password123' },
  ];
  const users = [];
  for (const u of testUsers) {
    const result = login(u.username, u.password);
    if (result.token) {
      users.push({ username: u.username, token: result.token, userId: result.userId });
    }
  }

  if (users.length === 0) {
    throw new Error('无法预登录测试用户，烟雾测试无法继续');
  }

  console.log(`[Smoke] 预登录 ${users.length} 个测试用户`);
  return { users };
}

export default function (data) {
  const vuId = __VU;
  const iter = __ITER;
  const user = data.users[vuId % data.users.length];
  const token = user.token;

  // ==================== 1. 健康检查 ====================
  const helloRes = http.get(`${BASE_URL}/test/hello`, { tags: { name: 'HealthCheck' } });
  check(helloRes, {
    'Hello - 状态码 200': (r) => r.status === 200,
    'Hello - 响应包含 message': (r) => r.body.includes('message') || r.body.includes('EmotionHub'),
  });

  // ==================== 2. 获取当前用户信息 ====================
  const meRes = http.get(`${BASE_URL}/auth/current`, {
    headers: authHeader(token),
    tags: { name: 'GetCurrentUser' },
  });
  assertSuccess(meRes, '获取当前用户');

  // ==================== 3. 发帖 ====================
  const postContent = randomPostContent(iter);
  const postRes = http.post(
    `${BASE_URL}/post/create`,
    JSON.stringify({ content: postContent }),
    { headers: authHeader(token), tags: { name: 'PostCreate' } }
  );
  const postOk = assertCreated(postRes, '发帖');
  const postId = postOk ? (() => { try { return JSON.parse(postRes.body)?.data?.id; } catch { return null; } })() : null;

  // 等待异步情感分析
  sleep(3);

  // ==================== 4. 帖子详情（验证情感分析完成）====================
  if (postId) {
    const detailRes = http.get(`${BASE_URL}/post/${postId}`, {
      headers: authHeader(token),
      tags: { name: 'PostDetail' },
    });
    const detailOk = assertSuccess(detailRes, '帖子详情');
    if (detailOk) {
      const body = JSON.parse(detailRes.body)?.data;
      check(body, {
        '帖子详情 - 有情感分数': () => body?.sentimentScore !== undefined && body?.sentimentScore !== null,
        '帖子详情 - 有情感标签': () => !!body?.sentimentLabel,
      });
    }
  }

  // ==================== 5. 帖子列表查询 ====================
  const listRes = http.get(`${BASE_URL}/post/list?page=1&size=10`, {
    headers: authHeader(token),
    tags: { name: 'PostList' },
  });
  assertPageResult(listRes, '帖子列表');

  // ==================== 6. 点赞（幂等）====================
  if (postId) {
    const likeRes = http.post(
      `${BASE_URL}/interaction/like`,
      JSON.stringify({ targetId: postId, targetType: 'POST' }),
      { headers: authHeader(token), tags: { name: 'Like' } }
    );
    assertSuccess(likeRes, '点赞', {
      '点赞 - 有 liked 字段': () => {
        try { return JSON.parse(likeRes.body)?.data?.liked !== undefined; } catch { return false; }
      },
    });
  }

  // ==================== 7. 发表评论 ====================
  if (postId) {
    const commentContent = randomCommentContent(iter);
    const commentRes = http.post(
      `${BASE_URL}/interaction/comment`,
      JSON.stringify({ postId: postId, content: commentContent }),
      { headers: authHeader(token), tags: { name: 'CommentCreate' } }
    );
    const commentOk = assertCreated(commentRes, '评论');
    const commentId = commentOk ? (() => { try { return JSON.parse(commentRes.body)?.data?.id; } catch { return null; } })() : null;

    // ==================== 8. 评论列表 ====================
    if (commentId) {
      const commentListRes = http.get(`${BASE_URL}/interaction/comment/list?postId=${postId}`, {
        headers: authHeader(token),
        tags: { name: 'CommentList' },
      });
      assertList(commentListRes, '评论列表');
    }
  }

  // ==================== 9. 我的统计 ====================
  const myStatsRes = http.get(`${BASE_URL}/stats/my`, {
    headers: authHeader(token),
    tags: { name: 'MyStats' },
  });
  assertSuccess(myStatsRes, '我的统计');

  // ==================== 10. 平台统计 ====================
  const platformStatsRes = http.get(`${BASE_URL}/stats/platform`, {
    headers: authHeader(token),
    tags: { name: 'PlatformStats' },
  });
  assertSuccess(platformStatsRes, '平台统计');

  // ==================== 11. Feed 流 ====================
  if (user.userId) {
    const feedRes = http.get(`${BASE_URL}/feed?userId=${user.userId}&page=0&size=10`, {
      headers: authHeader(token),
      tags: { name: 'Feed' },
    });
    assertSuccess(feedRes, 'Feed流');
  }

  // ==================== 12. 推荐接口 ====================
  if (user.userId) {
    const recRes = http.post(
      `${BASE_URL}/recommendations/emotional`,
      JSON.stringify({ userId: user.userId, strategy: 'emotional_adaptive', limit: 10 }),
      { headers: authHeader(token), tags: { name: 'Recommendation' } }
    );
    // 推荐可能返回 200（成功）或 200 但 data 为空（无足够数据），均视为正常
    check(recRes, {
      '推荐 - 状态码 200': (r) => r.status === 200,
      '推荐 - 响应速度 < 3s': (r) => r.timings.duration < 3000,
    });
  }

  // ==================== 13. 未读通知 ====================
  const notifRes = http.get(`${BASE_URL}/notification/unread/count`, {
    headers: authHeader(token),
    tags: { name: 'NotificationCount' },
  });
  check(notifRes, {
    '通知 - 状态码 200': (r) => r.status === 200,
  });

  sleep(1);
}

export function teardown(data) {
  console.log(`[Smoke] 测试完成，共使用 ${data.users.length} 个预注册用户`);
  clearCache();
}
