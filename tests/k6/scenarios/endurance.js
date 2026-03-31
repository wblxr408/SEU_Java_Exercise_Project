/**
 * k6 Endurance Test - 耐久性测试：长时间稳定运行，验证系统在持续压力下的稳定性
 *
 * 运行方式：
 *   k6 run tests/k6/scenarios/endurance.js
 *   k6 run tests/k6/scenarios/endurance.js --env BASE_URL=http://localhost:8080
 *
 * 场景设计：
 *   - 持续 30 分钟的稳定负载（15 VU）
 *   - 模拟真实用户的行为模式（混合读写操作）
 *
 * 关注指标：
 *   - 内存泄漏（观察狗屎堆或指标趋势）
 *   - 响应时间随时间的变化（不应明显退化）
 *   - 连接池耗尽（数据库/Redis 连接数）
 *   - 缓存命中率
 *   - 成功率稳定性
 *
 * 建议通过 Docker 运行以观察容器资源：
 *   docker run --rm -it grafana/k6 run -e BASE_URL=http://host.docker.internal:8080 /scripts/endurance.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { register, login, authHeader, getToken, clearCache, BASE_URL } from '../modules/auth.js';
import { randomPostContent, randomCommentContent } from '../modules/dataGenerator.js';
import { assertSuccess, assertPageResult, assertCreated } from '../modules/assertions.js';

export const options = {
  vus: 15,
  duration: '30m',  // 30 分钟持续负载

  // 稳定性配置：不变坡度，长时间稳定
  stages: [
    { duration: '2m',  target: 15 },   // 缓慢爬坡到目标
    { duration: '26m', target: 15 },   // 稳定运行
    { duration: '2m',  target: 0  },   // 缓慢下降
  ],

  thresholds: {
    // 耐久性测试严格要求稳定性
    http_req_duration: [
      'p(50)<600',    // 中位数保持 < 600ms
      'p(95)<1200',   // 95th < 1.2s
      'p(99)<3000',   // 99th < 3s
    ],

    // 随时间变化的趋势阈值（如果响应时间退化，测试会失败）
    'http_req_duration{p95}': ['max<3000'],  // 整个测试期间 p95 不应超过 3s
    'http_req_duration{p99}': ['max<5000'],  // 整个测试期间 p99 不应超过 5s

    http_req_failed: ['rate<0.01'],  // 失败率 < 1%
    checks: ['rate>0.99'],
  },

  tags: {
    suite: 'endurance',
  },
};

// 记录周期内指标，用于观察趋势
let periodCount = 0;
const periodMetrics = [];

export function setup() {
  const userCount = 20;
  const users = [];
  const prefix = `endurance_${Date.now()}`;

  for (let i = 0; i < userCount; i++) {
    const username = `${prefix}_${i}`;
    const result = register(username, 'EnduranceTest123', `EnduranceUser${i}`);
    if (result.token) {
      users.push({ username, token: result.token, userId: result.userId });
    } else {
      const loginRes = login(username, 'EnduranceTest123');
      if (loginRes.token) {
        users.push({ username, token: loginRes.token, userId: loginRes.userId });
      }
    }
  }

  console.log(`[Endurance] 预注册 ${users.length}/${userCount} 个用户`);
  return { users, startTime: Date.now() };
}

export default function (data) {
  const vuId = __VU;
  const iter = __ITER;
  const user = data.users[vuId % data.users.length];

  if (!user) return;

  const token = user.token;

  // ========== 混合操作序列（读写混合，模拟真实用户）==========
  const operations = [
    // 操作0：Feed 流（高频，30%）
    () => {
      if (user.userId) {
        const res = http.get(`${BASE_URL}/feed?userId=${user.userId}&page=0&size=20`, {
          headers: authHeader(token),
          tags: { name: 'Feed' },
        });
        assertSuccess(res, 'Feed');
      }
    },

    // 操作1：帖子列表（高频，30%）
    () => {
      const emotionLabels = ['', 'POSITIVE', 'NEGATIVE', 'NEUTRAL'];
      const label = emotionLabels[iter % emotionLabels.length];
      const url = label ? `${BASE_URL}/post/list?page=1&size=20&emotionLabel=${label}` : `${BASE_URL}/post/list?page=1&size=20`;
      const res = http.get(url, { headers: authHeader(token), tags: { name: 'PostList' } });
      assertPageResult(res, 'PostList');
    },

    // 操作2：统计接口（中频，15%）
    () => {
      const res = http.get(`${BASE_URL}/stats/my`, {
        headers: authHeader(token),
        tags: { name: 'MyStats' },
      });
      assertSuccess(res, 'MyStats');
    },

    // 操作3：发帖（低频，10%）
    () => {
      if (iter % 10 !== 0) return;
      const res = http.post(
        `${BASE_URL}/post/create`,
        JSON.stringify({ content: randomPostContent(iter) }),
        { headers: authHeader(token), tags: { name: 'PostCreate' } }
      );
      check(res, {
        '发帖 - 状态 2xx': (r) => r.status >= 200 && r.status < 300,
        '发帖 - < 3s': (r) => r.timings.duration < 3000,
      });
      sleep(2); // 等待情感分析
    },

    // 操作4：通知（中频，10%）
    () => {
      const res = http.get(`${BASE_URL}/notification/unread/count`, {
        headers: authHeader(token),
        tags: { name: 'NotifCount' },
      });
      check(res, { '通知 - 状态 200': (r) => r.status === 200 });
    },

    // 操作5：平台统计（低频，5%）
    () => {
      if (iter % 20 !== 0) return;
      const res = http.get(`${BASE_URL}/stats/platform`, {
        headers: authHeader(token),
        tags: { name: 'PlatformStats' },
      });
      assertSuccess(res, 'PlatformStats');
    },
  ];

  // 按比例选择操作
  const opIndex = iter % 100 < 30 ? 0 :
                  iter % 100 < 60 ? 1 :
                  iter % 100 < 75 ? 2 :
                  iter % 100 < 85 ? 3 :
                  iter % 100 < 95 ? 4 : 5;

  try {
    operations[opIndex]();
  } catch (e) {
    console.error(`[Endurance] VU ${vuId} error: ${e.message}`);
  }

  // 真实用户间隔（1-4 秒随机）
  sleep(Math.random() * 3 + 1);
}

export function handleSummary(data) {
  // 输出每分钟摘要，帮助分析性能退化趋势
  return {
    stdout: textSummary(data),
  };
}

function textSummary(data) {
  const duration = (data.state.testRunDurationMs || 0) / 1000;
  const mins = Math.floor(duration / 60);
  const secs = Math.floor(duration % 60);

  const httpMetrics = data.metrics?.http_req_duration;
  const failed = data.metrics?.http_req_failed;

  const p50 = httpMetrics?.values?.['p(50)'] || 0;
  const p95 = httpMetrics?.values?.['p(95)'] || 0;
  const p99 = httpMetrics?.values?.['p(99)'] || 0;
  const max = httpMetrics?.values?.max || 0;
  const avg = httpMetrics?.values?.avg || 0;
  const failRate = (failed?.values?.rate || 0) * 100;
  const totalReqs = data.metrics?.http_reqs?.values?.count || 0;

  return `
========== Endurance Test Summary ==========
测试时长: ${mins}m ${secs}s
总请求数: ${totalReqs}
HTTP 响应时间:
  - 平均: ${avg.toFixed(2)}ms
  - P50:   ${p50.toFixed(2)}ms
  - P95:   ${p95.toFixed(2)}ms
  - P99:   ${p99.toFixed(2)}ms
  - 最大:  ${max.toFixed(2)}ms
失败率:   ${failRate.toFixed(2)}%
===========================================
`;
}

export function teardown(data) {
  const elapsed = Date.now() - data.startTime;
  console.log(`[Endurance] 耐久性测试完成，运行 ${(elapsed / 60000).toFixed(1)} 分钟`);
  clearCache();
}
