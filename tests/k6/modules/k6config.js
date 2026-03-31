/**
 * k6 统一运行配置
 *
 * 此文件定义了测试输出目标：
 *   - console：纯文本输出到终端（默认、快速反馈）
 *   - json-file：JSON 输出到文件（供后续分析）
 *   - influxdb：通过 HTTP 导出到 InfluxDB（配合 Grafana 使用）
 *
 * 使用方式：
 *   k6 run tests/k6/scenarios/smoke.js
 *   k6 run tests/k6/scenarios/smoke.js -o json-file=results/smoke.json
 *   k6 run tests/k6/scenarios/load.js -o influxdb=http://localhost:8086
 *
 * 或通过环境变量：
 *   K6_OUT=influxdb k6 run tests/k6/scenarios/load.js
 */

import { Counter, Trend, Gauge } from 'k6/metrics';

// ========== 自定义指标 ==========

export const httpReqs = new Counter('custom_http_requests');
export const postCreates = new Counter('custom_post_creates');
export const authSuccess = new Counter('custom_auth_success');
export const authFail = new Counter('custom_auth_fail');

export const respFeed = new Trend('resp_feed');
export const respPost = new Trend('resp_post');
export const respStats = new Trend('resp_stats');
export const respLike = new Trend('resp_like');
export const respComment = new Trend('resp_comment');

export const errorRate = new Gauge('custom_error_rate');

let totalRequests = 0;
let failedRequests = 0;

/**
 * 记录请求（更新自定义指标）
 * @param {string} name - 请求名称
 * @param {Response} res - HTTP 响应
 * @param {Trend} trend - 趋势指标
 */
export function recordRequest(name, res, trend) {
  httpReqs.add(1);
  totalRequests++;
  trend.add(res.timings.duration);

  if (res.status >= 400) {
    failedRequests++;
  }

  // 每 100 次请求更新一次错误率
  if (totalRequests % 100 === 0 && totalRequests > 0) {
    errorRate.add(failedRequests / totalRequests);
  }
}

/**
 * 获取当前错误率
 */
export function getErrorRate() {
  return totalRequests > 0 ? failedRequests / totalRequests : 0;
}

/**
 * 获取总请求数
 */
export function getTotalRequests() {
  return totalRequests;
}
