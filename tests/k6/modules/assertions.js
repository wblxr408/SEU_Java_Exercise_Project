/**
 * 通用断言模块 - 统一 HTTP 响应校验
 */

import { check } from 'k6';
import { RateExtractor } from './metrics.js';

/**
 * 解析 JSON body（安全）
 * @param {Response} res
 * @returns {object|null}
 */
export function parseBody(res) {
  try {
    return JSON.parse(res.body);
  } catch {
    return null;
  }
}

/**
 * 通用成功校验（适用于大多数 REST 接口）
 * @param {Response} res
 * @param {string} label - 检查项标签
 * @param {object} extra - 额外检查项 { '子项名': (body) => boolean }
 */
export function assertSuccess(res, label = 'API', extra = {}) {
  const body = parseBody(res);
  return check(res, {
    [`${label} - 状态码 200`]: (r) => r.status === 200,
    [`${label} - 响应时间 < 2s`]: (r) => r.timings.duration < 2000,
    [`${label} - code=200`]: () => body?.code === 200,
    [`${label} - data 不为 null`]: () => body?.data !== undefined,
    ...extra,
  });
}

/**
 * 分页响应校验
 * @param {Response} res
 * @param {string} label
 */
export function assertPageResult(res, label = '分页') {
  const body = parseBody(res);
  return check(res, {
    [`${label} - 返回 pageResult`]: () => body?.data?.records !== undefined,
    [`${label} - total 字段存在`]: () => typeof body?.data?.total === 'number',
    [`${label} - records 是数组`]: () => Array.isArray(body?.data?.records),
  });
}

/**
 * 校验 4xx/5xx 错误响应
 * @param {Response} res
 * @param {number} expectedStatus
 */
export function assertError(res, expectedStatus = 401) {
  return check(res, {
    [`错误响应 - 状态码 ${expectedStatus}`]: (r) => r.status === expectedStatus,
    [`错误响应 - 有错误信息`]: () => !!parseBody(res)?.message,
  });
}

/**
 * 校验列表响应（简单数组）
 * @param {Response} res
 * @param {string} label
 */
export function assertList(res, label = '列表') {
  const body = parseBody(res);
  return check(res, {
    [`${label} - 状态码 200`]: (r) => r.status === 200,
    [`${label} - data 是数组`]: () => Array.isArray(body?.data),
  });
}

/**
 * 校验新增资源（201 或 200，且返回 ID）
 * @param {Response} res
 * @param {string} label
 */
export function assertCreated(res, label = '创建') {
  const body = parseBody(res);
  return check(res, {
    [`${label} - 状态码 2xx`]: (r) => r.status >= 200 && r.status < 300,
    [`${label} - 返回 ID`]: () => !!body?.data?.id,
  });
}

/**
 * 校验无数据响应（如删除成功）
 * @param {Response} res
 * @param {string} label
 */
export function assertVoid(res, label = '操作') {
  return check(res, {
    [`${label} - 状态码 200`]: (r) => r.status === 200,
  });
}
