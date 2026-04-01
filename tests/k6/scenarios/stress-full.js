/**
 * EmotionHub 压力测试 - 综合性能压测
 *
 * 运行方式：
 *   k6 run tests/k6/scenarios/stress-full.js
 *   k6 run tests/k6/scenarios/stress-full.js --env BASE_URL=http://localhost:8081/api
 *   k6 run tests/k6/scenarios/stress-full.js --env BASE_URL=http://localhost:8081/api -o json=results/stress_full_$(date +%Y%m%d_%H%M%S).json
 */

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { register, login, authHeader, clearCache } from '../modules/auth.js';
import { randomPostContent, randomCommentContent } from '../modules/dataGenerator.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081/api';

// ========== 测试配置 ==========
export const options = {
  vus: 1,
  stages: [
    { duration: '1m', target: 20  },   // 阶段1: 低负载
    { duration: '1m', target: 50  },   // 阶段2: 中等负载
    { duration: '1m', target: 100 },   // 阶段3: 较高负载
    { duration: '1m', target: 150 },   // 阶段4: 高负载
    { duration: '1m', target: 200 },   // 阶段5: 峰值压力
    { duration: '2m', target: 500 },   // 阶段6: 极限压力
    { duration: '1m', target: 0   },   // 冷却
  ],

  thresholds: {
    // 全局阈值
    'http_req_duration':        ['p(50)<500',  'p(95)<1500', 'p(99)<4000'],
    'http_req_failed':          ['rate<0.05'],

    // 分模块阈值
    'http_req_duration{group:::auth}':         ['p(95)<1000'],
    'http_req_duration{group:::feed}':         ['p(95)<2000'],
    'http_req_duration{group:::post}':         ['p(95)<1500'],
    'http_req_duration{group:::interaction}':  ['p(95)<1000'],
    'http_req_duration{group:::notification}': ['p(95)<800'],
    'http_req_duration{group:::stats}':         ['p(95)<500'],
  },

  tags: {
    suite: 'stress-full',
  },
};

// ========== 全局数据池 ==========
let sharedPostIds = [];
let sharedUsers  = [];

export function setup() {
  const NUM_USERS = 100;
  const NUM_POSTS = 200;
  const users = [];
  const prefix = `p${String(Date.now()).slice(-9)}`;

  // 1. 批量注册用户
  for (let i = 0; i < NUM_USERS; i++) {
    const username = `${prefix}_u${i}`;
    let result = register(username, 'PerfTest123', `PerfUser${i}`);
    // 调试：打印第一次注册结果
    if (i === 0) {
      console.log(`[Setup] 第一次注册: status=${result.response?.status}, body=${result.response?.body}`);
    }
    if (!result.token) {
      result = login(username, 'PerfTest123');
      if (i === 0) {
        console.log(`[Setup] login fallback: status=${result.response?.status}, body=${result.response?.body}`);
      }
    }
    if (result.token) {
      users.push({ username, token: result.token, userId: result.userId });
    }
  }
  console.log(`[Setup] 注册完成: ${users.length}/${NUM_USERS} 个用户`);

  // 2. 创建共享帖子（由前10个用户各发20条）
  const posters = users.slice(0, 10);
  const postIds = [];
  for (const poster of posters) {
    for (let j = 0; j < 20; j++) {
      const res = http.post(
        `${BASE_URL}/post/create`,
        JSON.stringify({ content: randomPostContent(j) }),
        { headers: authHeader(poster.token), tags: { name: 'SetupPost' } }
      );
      if (res.status === 200) {
        try {
          const id = JSON.parse(res.body)?.data?.id;
          if (id) postIds.push(id);
        } catch {}
      }
    }
  }
  console.log(`[Setup] 创建完成: ${postIds.length} 条帖子`);

  return { users, postIds };
}

export default function (data) {
  if (!data.users || !data.postIds || data.users.length === 0) return;

  if (sharedUsers.length === 0) {
    sharedUsers = data.users;
    sharedPostIds = data.postIds;
  }

  const vuId = __VU;
  const iter = __ITER;
  const user = sharedUsers[vuId % sharedUsers.length];
  if (!user) return;

  const token = user.token;

  // ========== Auth 模块 ==========
  group('auth', () => {
    const res = http.get(`${BASE_URL}/auth/current`, {
      headers: authHeader(token),
      tags: { name: 'AuthCurrent' },
    });
    check(res, {
      'auth - status 200':              (r) => r.status === 200,
      'auth - 响应时间 < 500ms':         (r) => r.timings.duration < 500,
    });
  });

  // ========== Feed 模块 ==========
  group('feed', () => {
    if (!user.userId) return;
    const res = http.get(`${BASE_URL}/feed?userId=${user.userId}&page=0&size=20`, {
      headers: authHeader(token),
      tags: { name: 'FeedList' },
    });
    check(res, {
      'feed - status 200':               (r) => r.status === 200,
      'feed - 有数据':                    (r) => {
        try {
          const body = JSON.parse(r.body);
          return r.status === 200 && body.code === 200;
        } catch { return false; }
      },
      'feed - 响应时间 < 1000ms':         (r) => r.timings.duration < 1000,
      'feed - 响应时间 < 2000ms':         (r) => r.timings.duration < 2000,
    });
  });

  // ========== Post 模块 ==========
  group('post', () => {
    // 帖子列表
    const listRes = http.get(`${BASE_URL}/post/list?page=1&size=20`, {
      headers: authHeader(token),
      tags: { name: 'PostList' },
    });
    check(listRes, {
      'post.list - status 200':          (r) => r.status === 200,
      'post.list - 响应时间 < 500ms':     (r) => r.timings.duration < 500,
    });

    // 发帖（低频）
    if (iter % 8 === 0 && sharedUsers.length > 0) {
      const poster = sharedUsers[(vuId + iter) % sharedUsers.length];
      const createRes = http.post(
        `${BASE_URL}/post/create`,
        JSON.stringify({ content: randomPostContent(iter) }),
        { headers: authHeader(poster.token), tags: { name: 'PostCreate' } }
      );
      check(createRes, {
        'post.create - status 200':       (r) => r.status === 200,
        'post.create - 响应时间 < 2000ms': (r) => r.timings.duration < 2000,
      });
    }

    // 帖子详情
    if (sharedPostIds.length > 0) {
      const detailId = sharedPostIds[(iter + vuId) % sharedPostIds.length];
      const detailRes = http.get(`${BASE_URL}/post/${detailId}`, {
        headers: authHeader(token),
        tags: { name: 'PostDetail' },
      });
      check(detailRes, {
        'post.detail - status 200':       (r) => r.status === 200,
        'post.detail - 响应时间 < 1000ms': (r) => r.timings.duration < 1000,
      });
    }
  });

  // ========== Interaction 模块（点赞+评论） ==========
  group('interaction', () => {
    if (sharedPostIds.length === 0) return;
    const targetPostId = sharedPostIds[iter % sharedPostIds.length];

    // 点赞
    const likeRes = http.post(
      `${BASE_URL}/interaction/like`,
      JSON.stringify({ targetId: targetPostId, targetType: 'POST' }),
      { headers: authHeader(token), tags: { name: 'LikeToggle' } }
    );
    check(likeRes, {
      'interaction.like - status 200':   (r) => r.status === 200,
      'interaction.like - 响应时间 < 500ms': (r) => r.timings.duration < 500,
    });

    // 评论
    const commentRes = http.post(
      `${BASE_URL}/interaction/comment`,
      JSON.stringify({ postId: targetPostId, content: randomCommentContent(iter) }),
      { headers: authHeader(token), tags: { name: 'CommentCreate' } }
    );
    check(commentRes, {
      'interaction.comment - status 200': (r) => r.status === 200,
      'interaction.comment - 响应时间 < 1000ms': (r) => r.timings.duration < 1000,
    });

    // 获取评论列表
    const commentListRes = http.get(`${BASE_URL}/interaction/comment/list?postId=${targetPostId}&page=0&size=20`, {
      headers: authHeader(token),
      tags: { name: 'CommentList' },
    });
    check(commentListRes, {
      'interaction.comment.list - status 200': (r) => r.status === 200,
    });
  });

  // ========== Notification 模块 ==========
  group('notification', () => {
    const res = http.get(`${BASE_URL}/notification/unread/count`, {
      headers: authHeader(token),
      tags: { name: 'NotifUnreadCount' },
    });
    check(res, {
      'notification.count - status 200': (r) => r.status === 200,
      'notification.count - 响应时间 < 300ms': (r) => r.timings.duration < 300,
    });
  });

  // ========== Stats 模块 ==========
  group('stats', () => {
    const res = http.get(`${BASE_URL}/stats/my`, {
      headers: authHeader(token),
      tags: { name: 'MyStats' },
    });
    check(res, {
      'stats - status 200':              (r) => r.status === 200,
      'stats - 响应时间 < 300ms':         (r) => r.timings.duration < 300,
    });
  });

  sleep(Math.random() * 1.5 + 0.3);
}

export function handleSummary(data) {
  const ts = new Date().toISOString().slice(0, 19).replace(/T/, '_').replace(/:/g, '-');
  const jsonFile = `results/stress_full_${ts}.json`;

  // 写 stdout 摘要
  const output = {
    timestamp: ts,
    suite: 'stress-full',
    baseUrl: BASE_URL,
    config: {
      vus: options.vus,
      stages: options.stages,
    },
    metrics: extractMetrics(data),
  };

  return {
    stdout: generateTextSummary(data),
    [jsonFile]: JSON.stringify(data),
  };
}

function extractMetrics(data) {
  const d = data.metrics;
  const toMs = (v) => (v || 0) / 1000; // k6 内部是秒，转毫秒

  const httpReqDuration = d.http_req_duration;
  const httpReqFailed   = d.http_req_failed;
  const checks          = d.checks;

  const extractPercentiles = (trend) => {
    if (!trend) return {};
    const v = trend.values;
    return {
      avg:    toMs(v.avg),
      med:    toMs(v.med),
      p90:    toMs(v['p(90)']),
      p95:    toMs(v['p(95)']),
      p99:    toMs(v['p(99)']),
      max:    toMs(v.max),
    };
  };

  const extractGroup = (groupName) => {
    const key = `http_req_duration{group:::${groupName}}`;
    return extractPercentiles(d[key]);
  };

  return {
    totalRequests:    httpReqDuration?.values?.count || 0,
    httpReqFailed:    httpReqFailed?.values?.rate || 0,
    checksPassRate:   checks?.values?.passes / Math.max(1, (checks?.values?.passes || 0) + (checks?.values?.fails || 0)),
    global:           extractPercentiles(httpReqDuration),
    auth:             extractGroup('auth'),
    feed:             extractGroup('feed'),
    post:             extractGroup('post'),
    interaction:      extractGroup('interaction'),
    notification:     extractGroup('notification'),
    stats:            extractGroup('stats'),
  };
}

function generateTextSummary(data) {
  const m = extractMetrics(data);
  const ts = new Date().toISOString().slice(0, 19);
  const lines = [];
  lines.push('========================================');
  lines.push('   EmotionHub 压力测试报告');
  lines.push('========================================');
  lines.push(`时间: ${ts}`);
  lines.push(`BASE_URL: ${__ENV.BASE_URL || 'http://localhost:8081/api'}`);
  lines.push(`配置: 15 VUs / 3 分钟（阶梯 10→15）`);
  lines.push('');
  lines.push('  █ TOTAL RESULTS');
  lines.push('');
  lines.push(`    HTTP 请求总数:  ${m.totalRequests}`);
  lines.push(`    HTTP 失败率:   ${(m.httpReqFailed * 100).toFixed(2)}%`);
  lines.push(`    Checks 通过率: ${(m.checksPassRate * 100).toFixed(2)}%`);
  lines.push('');
  lines.push('    █ 全局响应时间 (ms)');
  lines.push(`      avg=${m.global.avg.toFixed(1)}ms  med=${m.global.med.toFixed(1)}ms  p90=${m.global.p90.toFixed(1)}ms  p95=${m.global.p95.toFixed(1)}ms  p99=${m.global.p99.toFixed(1)}ms  max=${m.global.max.toFixed(1)}ms`);
  lines.push('');
  lines.push('    █ 分模块 p95 响应时间 (ms)');
  const modules = ['auth', 'feed', 'post', 'interaction', 'notification', 'stats'];
  for (const mod of modules) {
    const p = m[mod] || {};
    const val = p.p95 ? `${p.p95.toFixed(1)}ms` : 'N/A';
    lines.push(`      ${mod.padEnd(14)} p95=${val}`);
  }
  lines.push('');
  lines.push('========================================');
  return lines.join('\n');
}

export function teardown(data) {
  console.log('[Stress-Full] 压测完成');
  clearCache();
}
