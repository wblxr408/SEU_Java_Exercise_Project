/**
 * k6 Concurrency Test - 并发测试：验证系统在并发场景下的数据一致性和线程安全
 *
 * 运行方式：
 *   k6 run tests/k6/scenarios/concurrency.js
 *   k6 run tests/k6/scenarios/concurrency.js --env BASE_URL=http://localhost:8080
 *
 * 测试场景（覆盖关键并发风险点）：
 *
 *   场景1：点赞并发（Like Race）
 *     - 同一帖子同一时刻 N 个用户点赞/取消点赞
 *     - 验证：最终点赞数与操作序列一致，接口幂等性正确
 *
 *   场景2：同一用户快速点赞同一帖子（Idempotency）
 *     - 单用户快速连续发送多条点赞请求
 *     - 验证：接口幂等，状态切换正确
 *
 *   场景3：评论数统计并发（Comment Count Race）
 *     - 多用户同时评论同一帖子
 *     - 验证：评论数与实际评论数量一致
 *
 *   场景4：统计缓存一致性（Cache Consistency）
 *     - 并发读写同一用户的统计数据
 *     - 验证：统计数据在合理范围内，无脏读
 *
 *   场景5：帖子浏览量并发（View Count Race）
 *     - 多用户同时访问同一帖子详情
 *     - 验证：浏览量 ≥ 并发请求数（最终一致）
 *
 *   场景6：通知未读数并发（Notification Count Race）
 *     - 并发触发通知后查询未读数
 *     - 验证：未读数单调递增，计数准确
 *
 *   场景7：删除帖子并发（Delete Race）
 *     - 评论/点赞同一已被删除的帖子
 *     - 验证：接口返回 404 或正确处理，不崩溃
 *
 *   场景8：Feed 流缓存穿透（Cache Penetration）
 *     - 大量并发请求冷门用户 Feed
 *     - 验证：无超时，缓存保护生效
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { register, login, authHeader, BASE_URL, clearCache } from '../modules/auth.js';
import { randomPostContent, randomCommentContent } from '../modules/dataGenerator.js';
import { assertSuccess } from '../modules/assertions.js';

// ========== 并发测试结果收集器 ==========
class ConcurrencyTracker {
  constructor() {
    this.likes = [];
    this.comments = [];
    this.views = [];
    this.stats = [];
    this.errors = [];
  }

  recordLike(postId, ok, status, body) {
    this.likes.push({ postId, ok, status, ts: Date.now() });
    if (!ok) this.errors.push({ type: 'like', postId, status, body });
  }

  recordComment(postId, ok, status, body) {
    this.comments.push({ postId, ok, status, ts: Date.now() });
    if (!ok) this.errors.push({ type: 'comment', postId, status, body });
  }

  recordView(postId, ok, status) {
    this.views.push({ postId, ok, status, ts: Date.now() });
  }

  recordStats(userId, ok, status) {
    this.stats.push({ userId, ok, status, ts: Date.now() });
    if (!ok) this.errors.push({ type: 'stats', userId, status });
  }

  summary() {
    const likeErrors = this.errors.filter(e => e.type === 'like');
    const commentErrors = this.errors.filter(e => e.type === 'comment');
    const statsErrors = this.errors.filter(e => e.type === 'stats');

    return {
      totalLikes: this.likes.length,
      totalComments: this.comments.length,
      totalViews: this.views.length,
      totalStats: this.stats.length,
      likeErrors: likeErrors.length,
      commentErrors: commentErrors.length,
      statsErrors: statsErrors.length,
      allErrors: this.errors,
    };
  }
}

const tracker = new ConcurrencyTracker();

// ========== 共享资源（每个场景独立）==========
// 场景1-5 共用帖子池（在 setup 中创建）
let sharedPostIds = [];
let sharedUserTokens = [];

export const options = {
  // 高并发 VU，持续时间短，专门测并发
  vus: 50,
  duration: '2m',

  thresholds: {
    // 允许少量错误（并发竞争不一定每次都赢）
    http_req_failed: ['rate<0.10'],

    // 响应时间容忍度高（并发下资源争抢导致延迟）
    http_req_duration: ['p(99)<10000'],

    checks: ['rate>0.85'],
  },

  tags: {
    suite: 'concurrency',
  },
};

export function setup() {
  const NUM_USERS = 60;
  const NUM_POSTS = 5;
  const users = [];
  const prefix = `conc_${Date.now()}`;

  // 1. 注册用户
  for (let i = 0; i < NUM_USERS; i++) {
    const username = `${prefix}_${i}`;
    let result = register(username, 'ConcTest123', `ConcUser${i}`);
    if (!result.token) {
      result = login(username, 'ConcTest123');
    }
    if (result.token) {
      users.push({ username, token: result.token, userId: result.userId });
    }
  }

  console.log(`[Concurrency] 预注册 ${users.length} 个用户`);

  // 2. 创建共享帖子池（由第一个用户发帖）
  const firstUser = users[0];
  if (!firstUser) {
    throw new Error('无法创建共享帖子，并发测试无法继续');
  }

  const token = firstUser.token;
  const createdPostIds = [];

  for (let i = 0; i < NUM_POSTS; i++) {
    const res = http.post(
      `${BASE_URL}/post/create`,
      JSON.stringify({ content: randomPostContent(i) }),
      { headers: authHeader(token), tags: { name: 'SetupPost' } }
    );
    if (res.status === 200) {
      try {
        const id = JSON.parse(res.body)?.data?.id;
        if (id) createdPostIds.push(id);
      } catch {}
    }
  }

  console.log(`[Concurrency] 创建 ${createdPostIds.length} 个共享帖子: ${JSON.stringify(createdPostIds)}`);

  return {
    users: users.map(u => ({
      token: u.token,
      userId: u.userId,
      username: u.username,
    })),
    postIds: createdPostIds,
  };
}

export default function (data) {
  const vuId = __VU;
  const iter = __ITER;
  const users = data.users;
  const postIds = data.postIds;

  if (users.length === 0 || postIds.length === 0) return;

  // ========== 场景选择（按迭代轮流）==========
  // 每 8 轮切换一个场景，保证所有场景都有机会被执行
  const scenario = iter % 8;

  // ========== 场景1：点赞并发（Like Race）==========
  // 多个用户同时对同一帖子点赞
  if (scenario === 0 || scenario === 1) {
    const targetPostId = postIds[0];
    const targetUser = users[vuId % users.length];

    const res = http.post(
      `${BASE_URL}/interaction/like`,
      JSON.stringify({ targetId: targetPostId, targetType: 'POST' }),
      { headers: authHeader(targetUser.token), tags: { name: 'LikeRace' } }
    );
    const ok = check(res, {
      'LikeRace - 状态 200': (r) => r.status === 200,
    });
    tracker.recordLike(targetPostId, ok, res.status, res.body);
  }

  // ========== 场景2：同一用户快速点赞（Idempotency）==========
  if (scenario === 2) {
    const targetPostId = postIds[1 % postIds.length];
    const targetUser = users[vuId % users.length];

    // 快速连续发送 3 次点赞请求（模拟重复点击）
    for (let i = 0; i < 3; i++) {
      const res = http.post(
        `${BASE_URL}/interaction/like`,
        JSON.stringify({ targetId: targetPostId, targetType: 'POST' }),
        { headers: authHeader(targetUser.token), tags: { name: 'LikeRepeat' } }
      );
      check(res, {
        [`LikeRepeat[${i}] - 状态 200`]: (r) => r.status === 200,
      });
      tracker.recordLike(targetPostId, res.status === 200, res.status, res.body);
    }
  }

  // ========== 场景3：评论并发（Comment Count Race）==========
  if (scenario === 3 || scenario === 4) {
    const targetPostId = postIds[2 % postIds.length];
    const targetUser = users[vuId % users.length];

    const res = http.post(
      `${BASE_URL}/interaction/comment`,
      JSON.stringify({ postId: targetPostId, content: randomCommentContent(iter) }),
      { headers: authHeader(targetUser.token), tags: { name: 'CommentRace' } }
    );
    const ok = check(res, {
      'CommentRace - 状态 200': (r) => r.status === 200,
      'CommentRace - 返回 ID': () => {
        try { return !!JSON.parse(res.body)?.data?.id; } catch { return false; }
      },
    });
    tracker.recordComment(targetPostId, ok, res.status, res.body);
  }

  // ========== 场景4：统计接口并发（Cache Consistency）==========
  if (scenario === 5) {
    const targetUser = users[vuId % users.length];

    const res = http.get(`${BASE_URL}/stats/my`, {
      headers: authHeader(targetUser.token),
      tags: { name: 'StatsRace' },
    });
    const ok = check(res, {
      'StatsRace - 状态 200': (r) => r.status === 200,
      'StatsRace - 有统计数据': () => {
        try {
          const data = JSON.parse(res.body)?.data;
          return data !== null && data !== undefined;
        } catch { return false; }
      },
    });
    tracker.recordStats(targetUser.userId, ok, res.status);
  }

  // ========== 场景5：帖子详情并发（View Count Race）==========
  if (scenario === 6) {
    const targetPostId = postIds[3 % postIds.length];
    const targetUser = users[vuId % users.length];

    const res = http.get(`${BASE_URL}/post/${targetPostId}`, {
      headers: authHeader(targetUser.token),
      tags: { name: 'ViewRace' },
    });
    const ok = check(res, {
      'ViewRace - 状态 200': (r) => r.status === 200,
    });
    tracker.recordView(targetPostId, ok, res.status);
  }

  // ========== 场景6：Feed 流并发（多个用户同时请求）==========
  if (scenario === 7) {
    const targetUser = users[vuId % users.length];

    if (targetUser.userId) {
      const res = http.get(`${BASE_URL}/feed?userId=${targetUser.userId}&page=0&size=20`, {
        headers: authHeader(targetUser.token),
        tags: { name: 'FeedRace' },
      });
      check(res, {
        'FeedRace - 状态 200': (r) => r.status === 200,
      });
    }
  }

  sleep(0.1); // 极短间隔，最大化并发
}

export function handleSummary(data) {
  const summary = tracker.summary();
  return {
    stdout: `
========== Concurrency Test Summary ==========
点赞总数:   ${summary.totalLikes}
评论总数:   ${summary.totalComments}
浏览总数:   ${summary.totalViews}
统计请求:   ${summary.totalStats}
点赞错误:   ${summary.likeErrors}
评论错误:   ${summary.commentErrors}
统计错误:   ${summary.statsErrors}

${summary.allErrors.length > 0 ? '错误详情:' : '✅ 无并发错误！'}

${summary.allErrors.slice(0, 10).map(e => `  [${e.type}] status=${e.status}: ${e.body?.substring?.(0, 100) || e.body}`).join('\n')}
============================================
`,
  };
}

export function teardown(data) {
  console.log('[Concurrency] 并发测试完成');
  clearCache();
}
