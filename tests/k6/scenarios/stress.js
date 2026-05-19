/**
 * k6 Stress Test - 压力测试：逐步加压，发现系统的性能瓶颈和断点
 *
 * 运行方式：
 *   k6 run tests/k6/scenarios/stress.js
 *   k6 run tests/k6/scenarios/stress.js --env BASE_URL=http://localhost:8080/api
 *
 * 场景设计（渐进式加压 + 更真实的工作负载）：
 *   - 阶段1（1min）：20 VU   预热
 *   - 阶段2（1min）：50 VU   低负载
 *   - 阶段3（1min）：80 VU   中等负载
 *   - 阶段4（1min）：100 VU  较高负载
 *   - 阶段5（1min）：150 VU  高负载
 *   - 阶段6（1min）：180 VU  峰值压力
 *   - 阶段7（1min）：200 VU  接近极限
 *   - 阶段8（1min）：300 VU  超压测试
 *   - 阶段9（1min）：500 VU  极限压力
 *   - 阶段10（1min）：0 VU   冷却
 *
 * 工作负载配比（模拟真实用户行为）：
 *   - Feed流（高频）：40%  → 用户刷信息流，最频繁
 *   - 帖子列表（中频）：30% → 浏览帖子
 *   - 通知统计（低频）：20% → 查看通知和统计
 *   - 发帖（低频）：5%     → 用户发帖，概率触发
 *   - 互动操作（低频）：5% → 点赞/评论
 *
 * 关注指标：
 *   - 各阶段的 p95/p99 响应时间变化趋势
 *   - 5xx 错误开始出现的 VU 数量
 *   - 限流触发率
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { register, login, authHeader, clearCache, BASE_URL } from '../modules/auth.js';
import { randomPostContent } from '../modules/dataGenerator.js';
import { assertSuccess, assertPageResult } from '../modules/assertions.js';

export const options = {
  vus: 500,
  stages: [
    { duration: '1m',  target: 20  },   // 预热
    { duration: '1m',  target: 50  },   // 低负载
    { duration: '1m',  target: 80  },   // 中等负载
    { duration: '1m',  target: 100 },   // 较高负载
    { duration: '1m',  target: 150 },   // 高负载
    { duration: '1m',  target: 180 },   // 峰值压力
    { duration: '1m',  target: 200 },   // 接近极限
    { duration: '1m',  target: 300 },   // 超压测试
    { duration: '1m',  target: 500 },   // 极限压力
    { duration: '1m',  target: 0   },   // 冷却
  ],

  thresholds: {
    http_req_duration: [
      'p(50)<500',     // p50 < 500ms
      'p(95)<3000',    // p95 < 3s
      'p(99)<8000',    // p99 < 8s
    ],
    http_req_failed: ['rate<0.15'],   // 允许少量错误（15%）
    checks: ['rate>0.80'],            // 80% 检查通过
  },

  tags: {
    suite: 'stress',
  },
};

// 工作负载类型权重
const WORKLOAD_TYPES = {
  FEED: 0,      // Feed流
  POST_LIST: 1,  // 帖子列表
  STATS: 2,     // 统计
  POST_CREATE: 3, // 发帖
  INTERACTION: 4, // 互动
};

// 权重数组（模拟真实用户行为）
const WORKLOAD_WEIGHTS = [
  { type: WORKLOAD_TYPES.FEED,       weight: 40 },  // 40%
  { type: WORKLOAD_TYPES.POST_LIST,  weight: 30 },  // 30%
  { type: WORKLOAD_TYPES.STATS,      weight: 20 },  // 20%
  { type: WORKLOAD_TYPES.POST_CREATE,weight: 5  },  // 5%
  { type: WORKLOAD_TYPES.INTERACTION,weight: 5  },  // 5%
];

export function setup() {
  const userCount = 200;
  const users = [];
  const prefix = `stress_${Date.now()}`;

  console.log('[Stress] 开始注册测试用户...');
  for (let i = 0; i < userCount; i++) {
    const username = `${prefix}_${i}`;
    const result = register(username, 'StressTest123', `StressUser${i}`);
    if (result.token) {
      users.push({ username, token: result.token, userId: result.userId });
    } else {
      // 注册失败则尝试登录
      const loginRes = login(username, 'StressTest123');
      if (loginRes.token) {
        users.push({ username, token: loginRes.token, userId: loginRes.userId });
      }
    }
    // 每注册20个用户输出一次进度
    if ((i + 1) % 20 === 0) {
      console.log(`[Stress] 已注册 ${users.length}/${i + 1} 个用户`);
    }
  }

  console.log(`[Stress] 预注册完成，共 ${users.length}/${userCount} 个用户`);
  return { users };
}

/**
 * 根据权重选择工作负载类型
 */
function selectWorkloadType() {
  const rand = Math.random() * 100;
  let cumulative = 0;
  for (const item of WORKLOAD_WEIGHTS) {
    cumulative += item.weight;
    if (rand < cumulative) {
      return item.type;
    }
  }
  return WORKLOAD_TYPES.FEED;
}

export default function (data) {
  const vuId = __VU;
  const iter = __ITER;
  const user = data.users[vuId % data.users.length];

  if (!user) return;

  const token = user.token;

  // 根据权重选择本次执行的工作负载
  const workloadType = selectWorkloadType();

  // ========== Feed 流 ==========
  if (workloadType === WORKLOAD_TYPES.FEED) {
    const res = http.get(`${BASE_URL}/feed?userId=${user.userId}&page=0&size=20`, {
      headers: authHeader(token),
      tags: { name: 'Feed' },
    });
    assertSuccess(res, 'Feed');
    sleep(0.1); // 极短间隔
    return;
  }

  // ========== 帖子列表 ==========
  if (workloadType === WORKLOAD_TYPES.POST_LIST) {
    const page = Math.floor(Math.random() * 5);
    const res = http.get(`${BASE_URL}/post/list?page=${page}&size=20`, {
      headers: authHeader(token),
      tags: { name: 'PostList' },
    });
    assertPageResult(res, 'PostList');
    sleep(0.1);
    return;
  }

  // ========== 统计接口 ==========
  if (workloadType === WORKLOAD_TYPES.STATS) {
    const res = http.get(`${BASE_URL}/stats/my`, {
      headers: authHeader(token),
      tags: { name: 'MyStats' },
    });
    assertSuccess(res, 'MyStats');

    // 同时检查通知数（同一批次）
    const notifRes = http.get(`${BASE_URL}/notification/unread/count`, {
      headers: authHeader(token),
      tags: { name: 'NotifCount' },
    });
    check(notifRes, { '通知 - 状态 200': (r) => r.status === 200 });
    sleep(0.1);
    return;
  }

  // ========== 发帖 ==========
  if (workloadType === WORKLOAD_TYPES.POST_CREATE) {
    const res = http.post(
      `${BASE_URL}/post/create`,
      JSON.stringify({ content: randomPostContent(iter) }),
      { headers: authHeader(token), tags: { name: 'PostCreate' } }
    );
    check(res, {
      '发帖 - 状态 2xx': (r) => r.status >= 200 && r.status < 300,
    });
    sleep(0.5); // 发帖后稍作休息
    return;
  }

  // ========== 互动（点赞/评论）==========
  if (workloadType === WORKLOAD_TYPES.INTERACTION) {
    // 先获取帖子列表，获取一个帖子ID
    const listRes = http.get(`${BASE_URL}/post/list?page=0&size=5`, {
      headers: authHeader(token),
      tags: { name: 'PostList' },
    });

    let postId = null;
    try {
      const body = JSON.parse(listRes.body);
      if (body?.data?.records && body.data.records.length > 0) {
        postId = body.data.records[0].id;
      }
    } catch {}

    if (postId) {
      if (Math.random() > 0.5) {
        // 点赞
        const likeRes = http.post(
          `${BASE_URL}/interaction/like`,
          JSON.stringify({ targetId: postId, targetType: 'POST' }),
          { headers: authHeader(token), tags: { name: 'Like' } }
        );
        check(likeRes, { '点赞 - 状态 2xx': (r) => r.status >= 200 && r.status < 300 });
      } else {
        // 评论列表
        const commentRes = http.get(`${BASE_URL}/interaction/comment/list?postId=${postId}`, {
          headers: authHeader(token),
          tags: { name: 'CommentList' },
        });
        check(commentRes, { '评论列表 - 状态 200': (r) => r.status === 200 });
      }
    }
    sleep(0.1);
    return;
  }

  sleep(0.1);
}

export function teardown(data) {
  console.log('[Stress] 压力测试完成');
  clearCache();
}
